/*
 * Copyright 2018 JUUL Labs, Inc.
 */

package com.juul.able.experimental

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED

data class GattCallbackConfig(
    val onCharacteristicChangedCapacity: Int = CONFLATED
)

@Suppress("TooManyFunctions") // We're at the mercy of Android's BluetoothGattCallback.
class GattCallback(config: GattCallbackConfig) : BluetoothGattCallback() {

    internal val onConnectionStateChange =
        BroadcastChannel<OnConnectionStateChange>(CONFLATED)
    internal val onCharacteristicChanged =
        BroadcastChannel<OnCharacteristicChanged>(config.onCharacteristicChangedCapacity)
    internal val onResponse = Channel<Any>(CONFLATED)

    fun close() {
        Able.verbose { "close → Begin" }

        onConnectionStateChange.close()
        onCharacteristicChanged.close()
        onResponse.close()

        Able.verbose { "close ← End" }
    }

    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: GattStatus,
        newState: GattState
    ) {
        Able.debug {
            val statusString = status.asGattConnectionStatusString()
            val stateString = newState.asGattStateString()
            "onConnectionStateChange ← status=$statusString, newState=$stateString"
        }
        onResponse.offer(OnConnectionStateChange(status, newState))
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: GattStatus) {
        Able.verbose { "onServicesDiscovered ← status=${status.asGattStatusString()}" }
        onResponse.offer(status)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: GattStatus
    ) {
        val value = characteristic.value
        Able.verbose {
            "onCharacteristicRead ← uuid=${characteristic.uuid}, value.size=${value.size}"
        }
        val event = OnCharacteristicRead(characteristic, value, status)
        onResponse.offer(event)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: GattStatus
    ) {
        Able.verbose {
            val uuid = characteristic.uuid
            "onCharacteristicWrite ← uuid=$uuid, status=${status.asGattStatusString()}"
        }
        onResponse.offer(OnCharacteristicWrite(characteristic, status))
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val value = characteristic.value
        Able.verbose {
            "onCharacteristicChanged ← uuid=${characteristic.uuid}, value.size=${value.size}"
        }
        onCharacteristicChanged.offer(OnCharacteristicChanged(characteristic, value))
    }

    override fun onDescriptorRead(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: GattStatus
    ) {
        val value = descriptor.value // todo: investigate if descriptor.value *reference* changes or *content* (i.e. do we need to copy bytes?)
        Able.verbose {
            val uuid = descriptor.uuid
            val size = value.size
            val gattStatus = status.asGattStatusString()
            "onDescriptorRead ← uuid=$uuid, value.size=$size, status=$gattStatus"
        }
        onResponse.offer(OnDescriptorRead(descriptor, value, status))
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: GattStatus
    ) {
        Able.verbose {
            "onDescriptorWrite ← uuid=${descriptor.uuid}, status=${status.asGattStatusString()}"
        }
        onResponse.offer(OnDescriptorWrite(descriptor, status))
    }

    override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
        Able.verbose { "onReliableWriteCompleted ← status=${status.asGattStatusString()}" }
        onResponse.offer(OnReliableWriteCompleted(status))
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Able.verbose { "onMtuChanged ← mtu=$mtu, status=${status.asGattStatusString()}" }
        onResponse.offer(OnMtuChanged(mtu, status))
    }
}
