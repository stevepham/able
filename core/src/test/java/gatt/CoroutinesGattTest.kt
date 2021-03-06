/*
 * Copyright 2020 JUUL Labs, Inc.
 */

package com.juul.able.test.gatt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.os.RemoteException
import com.juul.able.gatt.ConnectionLostException
import com.juul.able.gatt.CoroutinesGatt
import com.juul.able.gatt.GattCallback
import com.juul.able.gatt.OnCharacteristicRead
import com.juul.able.gatt.OnCharacteristicWrite
import com.juul.able.gatt.OnDescriptorWrite
import com.juul.able.gatt.OnReadRemoteRssi
import com.juul.able.gatt.OutOfOrderGattCallbackException
import com.juul.able.gatt.writeCharacteristic
import com.juul.able.test.gatt.FakeBluetoothGattCharacteristic as FakeCharacteristic
import com.juul.able.test.gatt.FakeBluetoothGattDescriptor as FakeDescriptor
import com.juul.able.test.logger.ConsoleLoggerTestRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.debug.junit4.CoroutinesTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule

private val testUuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

class CoroutinesGattTest {

    @get:Rule
    val timeoutRule = CoroutinesTimeout.seconds(10)

    @get:Rule
    val loggerRule = ConsoleLoggerTestRule()

    @Test
    fun `discoverServices propagates result`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { discoverServices() } answers {
                    callback.onServicesDiscovered(this@mockk, GATT_SUCCESS)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            val response = runBlocking {
                gatt.discoverServices()
            }

            assertEquals(
                expected = GATT_SUCCESS,
                actual = response
            )
        }
    }

    @Test
    fun `discoverServices throws RemoteException when BluetoothGatt returns false`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { discoverServices() } returns false
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            assertFailsWith<RemoteException> {
                runBlocking {
                    gatt.discoverServices()
                }
            }
        }
    }

    @Test
    fun `readCharacteristic propagates result`() {
        val characteristic = FakeCharacteristic(testUuid)
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val value = ByteArray(256) { it.toByte() }
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { readCharacteristic(characteristic) } answers {
                    characteristic.value = value
                    callback.onCharacteristicRead(this@mockk, characteristic, GATT_SUCCESS)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            val response = runBlocking {
                gatt.readCharacteristic(characteristic)
            }

            assertEquals(
                expected = OnCharacteristicRead(characteristic, value, GATT_SUCCESS),
                actual = response
            )
        }
    }

    @Test
    fun `readCharacteristic throws RemoteException when BluetoothGatt returns false`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { readCharacteristic(any()) } returns false
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)

            assertFailsWith<RemoteException> {
                runBlocking {
                    gatt.readCharacteristic(createCharacteristic())
                }
            }
        }
    }

    @Test
    fun `writeCharacteristic propagates result`() {
        val characteristic = FakeCharacteristic(testUuid)
        val slot = slot<BluetoothGattCharacteristic>()
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { writeCharacteristic(capture(slot)) } answers {
                    callback.onCharacteristicWrite(this@mockk, characteristic, GATT_SUCCESS)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            val response = runBlocking {
                gatt.writeCharacteristic(characteristic, byteArrayOf(), WRITE_TYPE_DEFAULT)
            }

            assertEquals(
                expected = OnCharacteristicWrite(characteristic, GATT_SUCCESS),
                actual = response
            )
        }
    }

    @Test
    fun `ByteArray passed as parameter of writeCharacteristic is applied to BluetoothGattCharacteristic`() {
        val characteristic = FakeCharacteristic(testUuid)
        val slot = slot<BluetoothGattCharacteristic>()
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { writeCharacteristic(capture(slot)) } answers {
                    callback.onCharacteristicWrite(this@mockk, characteristic, GATT_SUCCESS)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            runBlocking {
                gatt.writeCharacteristic(characteristic, byteArrayOf(0xF, 0x0, 0x0, 0xD))
            }

            // Convert to `List` so equality verification is done on the contents.
            assertEquals(
                expected = byteArrayOf(0xF, 0x0, 0x0, 0xD).toList(),
                actual = slot.captured.value.toList()
            )
        }
    }

    @Test
    fun `writeCharacteristic throws RemoteException when BluetoothGatt returns false`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { writeCharacteristic(any()) } returns false
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)

            assertFailsWith<RemoteException> {
                runBlocking {
                    gatt.writeCharacteristic(createCharacteristic(), byteArrayOf())
                }
            }
        }
    }

    @Test
    fun `writeDescriptor propagates result`() {
        val descriptor = FakeDescriptor(testUuid)
        val slot = slot<BluetoothGattDescriptor>()
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { writeDescriptor(capture(slot)) } answers {
                    callback.onDescriptorWrite(this@mockk, descriptor, GATT_SUCCESS)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            val response = runBlocking {
                gatt.writeDescriptor(descriptor, byteArrayOf())
            }

            assertEquals(
                expected = OnDescriptorWrite(descriptor, GATT_SUCCESS),
                actual = response
            )
        }
    }

    @Test
    fun `ByteArray passed as parameter of writeDescriptor is applied to BluetoothGattDescriptor`() {
        val descriptor = FakeDescriptor(testUuid)
        val slot = slot<BluetoothGattDescriptor>()
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { writeDescriptor(capture(slot)) } answers {
                    callback.onDescriptorWrite(this@mockk, descriptor, GATT_SUCCESS)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            runBlocking {
                gatt.writeDescriptor(descriptor, byteArrayOf(0xF, 0x0, 0x0, 0xD))
            }

            // Convert to `List` so equality verification is done on the contents.
            assertEquals(
                expected = byteArrayOf(0xF, 0x0, 0x0, 0xD).toList(),
                actual = slot.captured.value.toList()
            )
        }
    }

    @Test
    fun `writeDescriptor throws RemoteException when BluetoothGatt returns false`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { writeDescriptor(any()) } returns false
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)

            assertFailsWith<RemoteException> {
                runBlocking {
                    gatt.writeDescriptor(createDescriptor(), byteArrayOf())
                }
            }
        }
    }

    @Test
    fun `readRemoteRssi propagates result`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { readRemoteRssi() } answers {
                    callback.onReadRemoteRssi(this@mockk, 1, GATT_SUCCESS)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            val response = runBlocking {
                gatt.readRemoteRssi()
            }

            assertEquals(
                expected = OnReadRemoteRssi(1, GATT_SUCCESS),
                actual = response
            )
        }
    }

    @Test
    fun `readRemoteRssi throws RemoteException when BluetoothGatt returns false`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { readRemoteRssi() } returns false
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)

            assertFailsWith<RemoteException> {
                runBlocking {
                    gatt.readRemoteRssi()
                }
            }
        }
    }

    @Test
    fun `readCharacteristic returning false does not cause a deadlock`() = runBlocking<Unit> {
        createDispatcher().use { dispatcher ->
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { readCharacteristic(any()) } returns false
                every<BluetoothDevice?> { device } returns createBluetoothDevice()
            }
            val callback = GattCallback(dispatcher)
            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)

            callback.onConnectionStateChange(bluetoothGatt, GATT_SUCCESS, STATE_CONNECTED)

            withTimeout(SECONDS.toMillis(10L)) {
                assertFailsWith<RemoteException>("First invocation") {
                    gatt.readCharacteristic(createCharacteristic())
                }

                // Perform another read to verify that the previous failure didn't deadlock.
                assertFailsWith<RemoteException>("Second invocation") {
                    gatt.readCharacteristic(createCharacteristic())
                }
            }
        }
    }

    @Test
    fun `Out-of-order BluetoothGattCallback call throws OutOfOrderGattCallback`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { readCharacteristic(any()) } answers {
                    callback.onDescriptorWrite(this@mockk, createDescriptor(), GATT_SUCCESS)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            runBlocking {
                assertFailsWith<OutOfOrderGattCallbackException> {
                    gatt.readCharacteristic(createCharacteristic())
                }
            }
        }
    }

    @Test
    fun `BluetoothGatt is closed on disconnect`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { close() } returns Unit
                every { device } returns createBluetoothDevice()
                every { disconnect() } answers {
                    callback.onConnectionStateChange(this@mockk, GATT_SUCCESS, STATE_DISCONNECTING)
                    callback.onConnectionStateChange(this@mockk, GATT_SUCCESS, STATE_DISCONNECTED)
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            runBlocking {
                gatt.disconnect()
            }

            verify(exactly = 1) { bluetoothGatt.close() }
        }
    }

    @Test
    fun `BluetoothGatt is closed on timeout during disconnect`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { close() } returns Unit
                every { device } returns createBluetoothDevice()
                every { disconnect() } answers {
                    callback.onConnectionStateChange(this@mockk, GATT_SUCCESS, STATE_DISCONNECTING)
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            runBlocking {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(200L) {
                        verify(exactly = 0) { bluetoothGatt.close() }
                        gatt.disconnect()
                    }
                }
            }

            verify(exactly = 1) { bluetoothGatt.close() }
        }
    }

    @Test
    fun `Gatt action throws ConnectionLostException if connection drops while executing request`() {
        createDispatcher().use { dispatcher ->
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { close() } returns Unit
                every { device } returns createBluetoothDevice()
                every { readCharacteristic(any()) } answers {
                    callback.onConnectionStateChange(this@mockk, GATT_SUCCESS, STATE_DISCONNECTED)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            runBlocking {
                val cause = assertFailsWith<ConnectionLostException> {
                    gatt.readCharacteristic(createCharacteristic())
                }.cause

                assertEquals<Class<out Throwable>>(
                    expected = ConnectionLostException::class.java,
                    actual = cause!!.javaClass
                )
            }

            verify(exactly = 1) { bluetoothGatt.close() }
        }
    }

    @Test
    fun `Gatt action honors cancellation while waiting on response`() {
        createDispatcher().use { dispatcher ->
            val didReadCharacteristic = Channel<Unit>(CONFLATED)
            val callback = GattCallback(dispatcher)
            val bluetoothGatt = mockk<BluetoothGatt> {
                every { close() } returns Unit
                every { device } returns createBluetoothDevice()
                every { readCharacteristic(any()) } answers {
                    didReadCharacteristic.offer(Unit)
                    true
                }
            }

            val gatt = CoroutinesGatt(bluetoothGatt, dispatcher, callback)
            runBlocking {
                val job = launch {
                    gatt.readCharacteristic(createCharacteristic())
                }

                didReadCharacteristic.receive()

                // Give `performBluetoothAction` some time to start "listening" for response (i.e.
                // time to reach `callback.onResponse.receive()` call in `performBluetoothAction`).
                delay(500L)

                // Throws an Exception if `performBluetoothAction` throws a
                // non-CancellationException on cancellation request, thereby validating that we
                // honored cancellation appropriately if this call doesn't throw an Exception.
                job.cancelAndJoin()
            }
        }
    }
}

private val dispatcherNumber = AtomicInteger()
private fun createDispatcher() =
    newSingleThreadContext("MockGatt${dispatcherNumber.incrementAndGet()}")

private fun createBluetoothDevice(): BluetoothDevice = mockk {
    every { this@mockk.toString() } returns "00:11:22:33:FF:EE"
}

private fun createCharacteristic(
    uuid: UUID = testUuid,
    data: ByteArray = byteArrayOf()
): BluetoothGattCharacteristic = mockk {
    every { getUuid() } returns uuid
    every { setValue(data) } returns true
    every { writeType = any() } returns Unit
    every { value } returns data
}

private fun createDescriptor(
    uuid: UUID = testUuid,
    data: ByteArray = byteArrayOf()
): BluetoothGattDescriptor = mockk {
    every { getUuid() } returns uuid
    every { setValue(data) } returns true
    every { value } returns data
}
