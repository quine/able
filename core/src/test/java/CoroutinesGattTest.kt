/*
 * Copyright 2018 JUUL Labs, Inc.
 */

package com.juul.able.experimental

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.os.RemoteException
import com.juul.able.experimental.messenger.GattCallback
import com.juul.able.experimental.messenger.GattCallbackConfig
import com.juul.able.experimental.messenger.Messenger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.BeforeClass
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoroutinesGattTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            Able.logger = NoOpLogger()
        }
    }

    @Test
    fun discoverServices_gattSuccess() = runBlocking {
        runDiscoverServicesGattStatusTest(GATT_SUCCESS)
    }

    @Test
    fun discoverServices_gattFailure() = runBlocking {
        runDiscoverServicesGattStatusTest(BluetoothGatt.GATT_FAILURE)
    }

    @Test
    fun discoverServices_bluetoothGattFailure() {
        val bluetoothGatt = mockk<BluetoothGatt> {
            every { discoverServices() } returns false
        }
        val callback = GattCallback(GattCallbackConfig())
        val messenger = Messenger(bluetoothGatt, callback)
        val gatt = CoroutinesGatt(bluetoothGatt, messenger)

        assertFailsWith(RemoteException::class) {
            runBlocking {
                gatt.discoverServices()
            }
        }
    }

    @Test
    fun readCharacteristic_gattSuccess() = runBlocking {
        runReadCharacteristicGattStatusTest(GATT_SUCCESS)
    }

    @Test
    fun readCharacteristic_gattFailure() = runBlocking {
        runReadCharacteristicGattStatusTest(BluetoothGatt.GATT_FAILURE)
    }

    @Test
    fun readCharacteristic_bluetoothGattFailure() {
        val characteristic = mockCharacteristic()
        val bluetoothGatt = mockk<BluetoothGatt> {
            every { readCharacteristic(characteristic) } returns false
        }
        val callback = GattCallback(GattCallbackConfig())
        val messenger = Messenger(bluetoothGatt, callback)
        val gatt = CoroutinesGatt(bluetoothGatt, messenger)

        assertFailsWith(RemoteException::class) {
            runBlocking {
                gatt.readCharacteristic(characteristic)
            }
        }
    }

    @Test
    fun writeDescriptor_gattSuccess() = runBlocking {
        runWriteDescriptorGattStatusTest(GATT_SUCCESS)
    }

    @Test
    fun writeDescriptor_gattFailure() = runBlocking {
        runWriteDescriptorGattStatusTest(BluetoothGatt.GATT_FAILURE)
    }

    @Test
    fun writeDescriptor_bluetoothGattFailure() {
        val descriptor = mockk<BluetoothGattDescriptor> {
            every { uuid } returns UUID.randomUUID()
            every { setValue(any()) } returns true
        }
        val value = ByteArray(10) { 0x1 }
        val bluetoothGatt = mockk<BluetoothGatt> {
            every { writeDescriptor(any()) } returns false
        }
        val callback = GattCallback(GattCallbackConfig())
        val messenger = Messenger(bluetoothGatt, callback)
        val gatt = CoroutinesGatt(bluetoothGatt, messenger)

        assertFailsWith(RemoteException::class) {
            runBlocking {
                gatt.writeDescriptor(descriptor, value)
            }
        }
    }

    /**
     * Verifies that [BluetoothGattCharacteristic]s that are received by the [GattCallback] remain
     * in-order when consumed from the [Gatt.onCharacteristicChanged] channel.
     */
    @Test
    fun correctOrderOfOnCharacteristicChanged() {
        val numberOfFakeCharacteristicNotifications = 10_000L
        val numberOfFakeBinderThreads = 10
        val onCharacteristicChangedCapacity = numberOfFakeCharacteristicNotifications.toInt()

        val bluetoothGatt = mockk<BluetoothGatt>()
        val callback = GattCallback(GattCallbackConfig(onCharacteristicChangedCapacity)).apply {
            onConnectionStateChange(bluetoothGatt, GATT_SUCCESS, STATE_CONNECTED)
        }
        val messenger = Messenger(bluetoothGatt, callback)
        val gatt = CoroutinesGatt(bluetoothGatt, messenger)

        val binderThreads = FakeBinderThreadHandler(numberOfFakeBinderThreads)
        for (i in 0..numberOfFakeCharacteristicNotifications) {
            binderThreads.enqueue {
                val characteristic = mockCharacteristic(data = i.asByteArray())
                callback.onCharacteristicChanged(bluetoothGatt, characteristic)
            }
        }

        runBlocking {
            var i = 0L
            gatt.onCharacteristicChanged.openSubscription().also { subscription ->
                binderThreads.start()

                subscription.consumeEach { (_, value) ->
                    assertEquals(i++, value.longValue)

                    if (i == numberOfFakeCharacteristicNotifications) {
                        subscription.cancel()
                    }
                }
            }
            assertEquals(numberOfFakeCharacteristicNotifications, i)

            binderThreads.stop()
        }
    }

    @Test
    fun readCharacteristic_bluetoothGattReturnsFalse_doesNotDeadlock() {
        val bluetoothGatt = mockk<BluetoothGatt> {
            every { readCharacteristic(any()) } returns false
        }
        val callback = GattCallback(GattCallbackConfig()).apply {
            onConnectionStateChange(bluetoothGatt, GATT_SUCCESS, STATE_CONNECTED)
        }
        val messenger = Messenger(bluetoothGatt, callback)
        val gatt = CoroutinesGatt(bluetoothGatt, messenger)

        assertFailsWith(RemoteException::class, "First invocation") {
            runBlocking {
                withTimeout(5_000L) {
                    gatt.readCharacteristic(mockCharacteristic())
                }
            }
        }

        // Perform another read to verify that the previous failure did not deadlock `Messenger`.
        assertFailsWith(RemoteException::class, "Second invocation") {
            runBlocking {
                withTimeout(5_000L) {
                    gatt.readCharacteristic(mockCharacteristic())
                }
            }
        }
    }
}

private suspend fun runDiscoverServicesGattStatusTest(gattStatus: GattStatus) {
    val bluetoothGatt = mockk<BluetoothGatt> {
        every { discoverServices() } returns true
    }
    val callback = GattCallback(GattCallbackConfig()).apply {
        onServicesDiscovered(bluetoothGatt, gattStatus)
    }
    val messenger = Messenger(bluetoothGatt, callback)
    val gatt = CoroutinesGatt(bluetoothGatt, messenger)

    val result = gatt.discoverServices()
    assertEquals(gattStatus, result)
}

private suspend fun runReadCharacteristicGattStatusTest(status: GattStatus) {
    val characteristic = mockCharacteristic()
    val bluetoothGatt = mockk<BluetoothGatt> {
        every { readCharacteristic(any()) } returns true
    }
    val callback = GattCallback(GattCallbackConfig()).apply {
        onCharacteristicRead(bluetoothGatt, characteristic, status)
    }
    val messenger = Messenger(bluetoothGatt, callback)
    val gatt = CoroutinesGatt(bluetoothGatt, messenger)

    val result = gatt.readCharacteristic(characteristic)
    assertEquals(characteristic, result.characteristic)
    assertEquals(status, result.status)
}

private suspend fun runWriteDescriptorGattStatusTest(status: GattStatus) {
    val descriptor = mockDescriptor()
    val bluetoothGatt = mockk<BluetoothGatt> {
        every { writeDescriptor(any()) } returns true
    }
    val callback = GattCallback(GattCallbackConfig()).apply {
        onDescriptorWrite(bluetoothGatt, descriptor, status)
    }
    val messenger = Messenger(bluetoothGatt, callback)
    val gatt = CoroutinesGatt(bluetoothGatt, messenger)

    val result = gatt.writeDescriptor(descriptor, descriptor.value)
    assertEquals(descriptor, result.descriptor)
    assertEquals(status, result.status)
}

private fun mockDescriptor(
    uuid: UUID = UUID.randomUUID(),
    data: ByteArray = ByteArray(255) { it.toByte() }
): BluetoothGattDescriptor = mockk {
    every { getUuid() } returns uuid
    every { setValue(data) } returns true
    every { value } returns data
}

private fun mockCharacteristic(
    uuid: UUID = UUID.randomUUID(),
    data: ByteArray = ByteArray(255) { it.toByte() }
): BluetoothGattCharacteristic = mockk {
    every { getUuid() } returns uuid
    every { setValue(data) } returns true
    every { writeType = any() } returns Unit
    every { value } returns data
}

private fun Long.asByteArray(): ByteArray = ByteBuffer.allocate(8).putLong(this).array()

private val ByteArray.longValue: Long
    get() = ByteBuffer.wrap(this).long
