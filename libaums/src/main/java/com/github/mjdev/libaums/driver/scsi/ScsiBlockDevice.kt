/*
 * (C) Copyright 2014 mjahnen <github@mgns.tech>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.github.mjdev.libaums.driver.scsi

import android.util.Log
import com.github.mjdev.libaums.UsbMassStorageDevice
import com.github.mjdev.libaums.driver.BlockDeviceDriver
import com.github.mjdev.libaums.driver.scsi.commands.*
import com.github.mjdev.libaums.driver.scsi.commands.CommandBlockWrapper.Direction
import com.github.mjdev.libaums.usb.JellyBeanMr2Communication
import com.github.mjdev.libaums.usb.UsbCommunication
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

class UnitNotReady: IOException("Device is not ready (Unsuccessful ScsiTestUnitReady Csw status)")

/**
 * This class is responsible for handling mass storage devices which follow the
 * SCSI standard. This class communicates with the mass storage device via the
 * different SCSI commands.
 *
 * @author mjahnen
 * @see com.github.mjdev.libaums.driver.scsi.commands
 */
class ScsiBlockDevice(private val usbCommunication: UsbCommunication, private val lun: Byte) : BlockDeviceDriver {
    private val outBuffer: ByteBuffer = ByteBuffer.allocate(31)
    private val cswBuffer: ByteBuffer = ByteBuffer.allocate(CommandStatusWrapper.SIZE)

    override var blockSize: Int = 0
        private set
    private var lastBlockAddress: Int = 0

    private val writeCommand = ScsiWrite10(lun=lun)
    private val readCommand = ScsiRead10(lun=lun)
    private val csw = CommandStatusWrapper()

    /**
     * The size of the block device, in blocks of [blockSize] bytes,
     *
     * @return The block device size in blocks
     */
    override val blocks: Long = lastBlockAddress.toLong()

    /**
     * Issues a SCSI Inquiry to determine the connected device. After that it is
     * checked if the unit is ready. Logs a warning if the unit is not ready.
     * Finally the capacity of the mass storage device is read.
     *
     * @throws IOException
     * If initialing fails due to an unsupported device or if
     * reading fails.
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiInquiry
     *
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiInquiryResponse
     *
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiTestUnitReady
     *
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiReadCapacity
     *
     * @see com.github.mjdev.libaums.driver.scsi.commands.ScsiReadCapacityResponse
     */
    @Throws(IOException::class)
    @Synchronized
    override fun init() {
        val inBuffer = ByteBuffer.allocate(36)
        val inquiry = ScsiInquiry(inBuffer.array().size.toByte(), lun=lun)
        transferCommand(inquiry, inBuffer)
        inBuffer.clear()
        val inquiryResponse = ScsiInquiryResponse.read(inBuffer)
        if (UsbMassStorageDevice.DEBUG_MODE)
            Log.d(TAG, "inquiry response: $inquiryResponse")

        if (inquiryResponse.peripheralQualifier.toInt() != 0 || inquiryResponse.peripheralDeviceType.toInt() != 0) {
            throw IOException("unsupported PeripheralQualifier or PeripheralDeviceType")
        }

        val testUnit = ScsiTestUnitReady(lun=lun)
        try {
            if (!transferCommand(testUnit, ByteBuffer.allocate(0))) {
                Log.e(TAG, "unit not ready!")
                throw UnitNotReady()
            }
        } catch (e: IOException) {
            if (e.message.equals("Unsuccessful Csw status: 1")) {
                throw UnitNotReady()
            } else {
                throw e
            }
        }

        val readCapacity = ScsiReadCapacity(lun=lun)
        inBuffer.clear()
        transferCommand(readCapacity, inBuffer)
        inBuffer.clear()
        val readCapacityResponse = ScsiReadCapacityResponse.read(inBuffer)
        blockSize = readCapacityResponse.blockLength
        lastBlockAddress = readCapacityResponse.logicalBlockAddress

        if (UsbMassStorageDevice.DEBUG_MODE) {
            Log.i(TAG, "Block size: $blockSize")
            Log.i(TAG, "Last block address: $lastBlockAddress")
        }
    }

    /**
     * Transfers the desired command to the device. If the command has a data
     * phase the parameter `inBuffer` is used to store or read data
     * to resp. from it. The direction of the data phase is determined by
     * [#getDirection()][com.github.mjdev.libaums.driver.scsi.commands.CommandBlockWrapper]
     * .
     *
     *
     * Return value is true if the status of the command status wrapper is
     * successful (
     * [#getbCswStatus()][com.github.mjdev.libaums.driver.scsi.commands.CommandStatusWrapper]
     * ).
     *
     * @param command
     * The command which should be transferred.
     * @param inBuffer
     * The buffer used for reading or writing.
     * @return True if the transaction was successful.
     * @throws IOException
     * If something fails.
     */
    val MAX_RETRY_COUNT = 3
    private var mRetryCounter: Int = 0
    @Throws(IOException::class)
    @Synchronized
    private fun transferCommand(command: CommandBlockWrapper, inBuffer: ByteBuffer): Boolean {
        try {
            var result = transferCommand(command, inBuffer, 0)
            this.mRetryCounter = 0
            return result
        } catch (e: Exception) {
            if (this.mRetryCounter < MAX_RETRY_COUNT) {
                if (UsbMassStorageDevice.DEBUG_MODE)
                    Log.e("TRANSFER_COMMAND_ERROR", "Error:", e)
                this.mRetryCounter++
                if (usbCommunication is JellyBeanMr2Communication) {
                    usbCommunication.usbMassStorageDevice.resetInterface()
                }
                return transferCommand(command, inBuffer)
            }
            throw e;
        }
    }

    @Throws(IOException::class)
    @Synchronized
    private fun transferCommand(command: CommandBlockWrapper, inBuffer: ByteBuffer, test: Int): Boolean {
        val outArray = outBuffer.array()
        Arrays.fill(outArray, 0.toByte())

        outBuffer.clear()
        command.serialize(outBuffer)
        outBuffer.clear()

        var written = usbCommunication.bulkOutTransfer(outBuffer)
        if (written != outArray.size) {
            throw IOException("Writing all bytes on command $command failed!")
        }

        val transferLength = command.dCbwDataTransferLength
        var read = 0
        if (transferLength > 0) {

            if (command.direction == Direction.IN) {
                do {
                    read += usbCommunication.bulkInTransfer(inBuffer)
                } while (read < transferLength)

                if (read != transferLength) {
                    throw IOException("Unexpected command size (" + read + ") on response to "
                            + command)
                }
            } else {
                written = 0
                do {
                    written += usbCommunication.bulkOutTransfer(inBuffer)
                } while (written < transferLength)

                if (written != transferLength) {
                    throw IOException("Could not write all bytes: $command")
                }
            }
        }


        // expecting csw now
        cswBuffer.clear()
        read = usbCommunication.bulkInTransfer(cswBuffer)
        if (read != CommandStatusWrapper.SIZE) {
            throw IOException("Unexpected command size while expecting csw")
        }
        cswBuffer.clear()

        csw.read(cswBuffer)
        if (csw.bCswStatus.toInt() != CommandStatusWrapper.COMMAND_PASSED) {
            throw IOException("Unsuccessful Csw status: " + csw.bCswStatus)
        }

        if (csw.dCswTag != command.dCbwTag) {
            throw IOException("wrong csw tag!")
        }

        return csw.bCswStatus.toInt() == CommandStatusWrapper.COMMAND_PASSED
    }

    /**
     * This method reads from the device at the specific device offset. The
     * devOffset specifies at which block the reading should begin. That means
     * the devOffset is not in bytes!
     */
    @Synchronized
    @Throws(IOException::class)
    override fun read(devOffset: Long, dest: ByteBuffer) {
        //long time = System.currentTimeMillis();
        require(dest.remaining() % blockSize == 0) { "dest.remaining() must be multiple of blockSize!" }

        readCommand.init(devOffset.toInt(), dest.remaining(), blockSize)
        //Log.d(TAG, "reading: " + read);

        transferCommand(readCommand, dest)
        dest.position(dest.limit())

        //Log.d(TAG, "read time: " + (System.currentTimeMillis() - time));
    }

    /**
     * This method writes from the device at the specific device offset. The
     * devOffset specifies at which block the writing should begin. That means
     * the devOffset is not in bytes!
     */
    @Synchronized
    @Throws(IOException::class)
    override fun write(devOffset: Long, src: ByteBuffer) {
        //long time = System.currentTimeMillis();
        require(src.remaining() % blockSize == 0) { "src.remaining() must be multiple of blockSize!" }

        writeCommand.init(devOffset.toInt(), src.remaining(), blockSize)
        //Log.d(TAG, "writing: " + write);

        transferCommand(writeCommand, src)
        src.position(src.limit())

        //Log.d(TAG, "write time: " + (System.currentTimeMillis() - time));
    }

    companion object {

        private val TAG = ScsiBlockDevice::class.java.simpleName
    }
}
