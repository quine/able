/*
 * Copyright 2018 JUUL Labs, Inc.
 */

@file:Suppress("RedundantUnitReturnType")

package com.juul.able.experimental

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.os.RemoteException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.filter
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.LazyThreadSafetyMode.NONE
import kotlin.coroutines.CoroutineContext

class GattClosed(message: String, cause: Throwable) : IllegalStateException(message, cause)
class GattConnectionLost : Exception()

class CoroutinesGatt(
    private val bluetoothGatt: BluetoothGatt,
    private val callback: GattCallback,
    private val dispatcher: CoroutineDispatcher = newSingleThreadContext("Gatt")
) : Gatt {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = job

    private val connectionStateMonitor by lazy { ConnectionStateMonitor(this) }

    override val onConnectionStateChange: BroadcastChannel<OnConnectionStateChange>
        get() = callback.onConnectionStateChange

    override val onCharacteristicChanged: BroadcastChannel<OnCharacteristicChanged>
        get() = callback.onCharacteristicChanged

    override fun requestConnect(): Boolean = bluetoothGatt.connect()
    override fun requestDisconnect(): Unit = bluetoothGatt.disconnect()

    override suspend fun connect(): Boolean {
        return if (requestConnect()) {
            connectionStateMonitor.suspendUntilConnectionState(STATE_CONNECTED)
        } else {
            Able.error { "connect → BluetoothGatt.requestConnect() returned false " }
            false
        }
    }

    override suspend fun disconnect(): Unit {
        requestDisconnect()
        connectionStateMonitor.suspendUntilConnectionState(STATE_DISCONNECTED)
    }

    override fun close() {
        Able.verbose { "close → Begin" }
        job.cancel()
        connectionStateMonitor.close()
        callback.close()

        if (dispatcher is ExecutorCoroutineDispatcher) {
            /**
             * Explicitly close context (this is needed until #261 is fixed).
             *
             * [Kotlin Coroutines Issue #261](https://github.com/Kotlin/kotlinx.coroutines/issues/261)
             * [Coroutines actor test Gist](https://gist.github.com/twyatt/c51f81d763a6ee39657233fa725f5435)
             */
            dispatcher.close()
        }

        bluetoothGatt.close()
        Able.verbose { "close → End" }
    }

    override val services: List<BluetoothGattService> get() = bluetoothGatt.services
    override fun getService(uuid: UUID): BluetoothGattService? = bluetoothGatt.getService(uuid)

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.discoverServices] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun discoverServices(): GattStatus =
        performBluetoothAction("discoverServices") {
            bluetoothGatt.discoverServices()
        }

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.readCharacteristic] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun readCharacteristic(
        characteristic: BluetoothGattCharacteristic
    ): OnCharacteristicRead =
        performBluetoothAction("readCharacteristic") {
            bluetoothGatt.readCharacteristic(characteristic)
        }

    /**
     * @param value applied to [characteristic] when characteristic is written.
     * @param writeType applied to [characteristic] when characteristic is written.
     * @throws [RemoteException] if underlying [BluetoothGatt.writeCharacteristic] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: WriteType
    ): OnCharacteristicWrite =
        performBluetoothAction("writeCharacteristic") {
            characteristic.value = value
            characteristic.writeType = writeType
            bluetoothGatt.writeCharacteristic(characteristic)
        }

    /**
     * @param value applied to [descriptor] when descriptor is written.
     * @throws [RemoteException] if underlying [BluetoothGatt.writeDescriptor] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun writeDescriptor(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): OnDescriptorWrite =
        performBluetoothAction("writeDescriptor") {
            descriptor.value = value
            bluetoothGatt.writeDescriptor(descriptor)
        }

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.requestMtu] returns `false`.
     * @throws [GattClosed] if [Gatt] is closed while method is executing.
     */
    override suspend fun requestMtu(mtu: Int): OnMtuChanged =
        performBluetoothAction("requestMtu") {
            bluetoothGatt.requestMtu(mtu)
        }

    override fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean
    ): Boolean {
        Able.info { "setCharacteristicNotification → uuid=${characteristic.uuid}, enable=$enable" }
        return bluetoothGatt.setCharacteristicNotification(characteristic, enable)
    }

    private val lock = Mutex()

    // Thread safety mode of NONE because access is protected by `lock`.
    private val isDisconnected by lazy(NONE) {
        onConnectionStateChange
            .openSubscription()
            .filter { (_, newState) ->
                newState == STATE_DISCONNECTING || newState == STATE_DISCONNECTED
            }
    }

    // todo: is inline/crossinline beneficial?
    private suspend inline fun <T> performBluetoothAction(
        methodName: String,
        crossinline action: () -> Boolean
    ): T = lock.withLock {
        Able.verbose { "$methodName → withContext" }
        withContext(dispatcher) {
            action.invoke() || throw RemoteException("BluetoothGatt.$methodName returned false")
        }

        Able.verbose { "$methodName ← Waiting for BluetoothGattCallback" }
        val response = try {
            select<T> {
                isDisconnected.onReceive { throw GattConnectionLost() } // fixme: if another action picks up the disconnected event will this trigger again?
                callback.onResponse.onReceive { it as T } // todo: suppress cast warning once deemed safe to do so
            }
        } catch (e: ClosedReceiveChannelException) {
            throw GattClosed("Gatt closed during $methodName", e)
        } catch (e: ClassCastException) {
            TODO() // todo: choose appropriate exception that signifies that Android callbacks occurred in an unexpected order
        }

        Able.info { "$methodName ← $response" }
        return response
    }
}
