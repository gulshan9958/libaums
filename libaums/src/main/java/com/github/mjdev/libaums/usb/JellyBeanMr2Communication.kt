package com.github.mjdev.libaums.usb

/**
 * Created by magnusja on 21/12/16.
 */

import android.annotation.TargetApi
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Build
import android.util.Log
import com.github.mjdev.libaums.ErrNo
import com.github.mjdev.libaums.UsbMassStorageDevice
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Usb communication which uses the newer API in Android Jelly Bean MR2 (API
 * level 18). It just delegates the calls to the [UsbDeviceConnection]
 * .
 *
 * @author mjahnen
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
internal class JellyBeanMr2Communication(private val deviceConnection: UsbDeviceConnection, private val outEndpoint: UsbEndpoint, private val inEndpoint: UsbEndpoint, public val usbMassStorageDevice: UsbMassStorageDevice) : UsbCommunication {

    @Throws(IOException::class)
    override fun bulkOutTransfer(src: ByteBuffer): Int {
        val length = getTransferLength(src, outEndpoint.maxPacketSize)
        //val result = deviceConnection.bulkTransfer(outEndpoint,
        //        src.array(), src.position(), src.remaining(), UsbCommunication.TRANSFER_TIMEOUT)
        val result = deviceConnection.bulkTransfer(outEndpoint,
                src.array(), src.position(), length, UsbCommunication.TRANSFER_TIMEOUT)
        if (UsbMassStorageDevice.DEBUG_MODE) {
            Log.e("Communication",
                    "OUT TRANSFER bufferSize ${src.array().size}| startPos ${src.position()} | length $length | readSize $result")
        }
        if (result == -1) {
            throw IOException("Could not write to device, result == -1 errno " + ErrNo.errno + " " + ErrNo.errstr)
        }

        src.position(src.position() + result)
        return result
    }

    @Throws(IOException::class)
    override fun bulkInTransfer(dest: ByteBuffer): Int {
        //val result = deviceConnection.bulkTransfer(inEndpoint,
        //        dest.array(), dest.position(), dest.remaining(), UsbCommunication.TRANSFER_TIMEOUT)

        val length = getTransferLength(dest, inEndpoint.maxPacketSize)
        val result = deviceConnection.bulkTransfer(inEndpoint,
                dest.array(), dest.position(), length, UsbCommunication.TRANSFER_TIMEOUT)
        if (UsbMassStorageDevice.DEBUG_MODE) {
            Log.e("Communication",
                    "IN TRANSFER bufferSize ${dest.array().size}| startPos ${dest.position()} | length $length | readSize $result")
        }

        if (result == -1) {
            throw IOException("Could not read from device, result == -1 errno " + ErrNo.errno + " " + ErrNo.errstr)
        }

        dest.position(dest.position() + result)
        return result
    }

    private fun getTransferLength(buffer: ByteBuffer, maxPacketSize: Int): Int {
        var blockSize = 16384//Default max packet size.
        if (maxPacketSize > 0) {
            blockSize = maxPacketSize
        }
        return when {
            //blockSize == 0 -> buffer.remaining()
            buffer.remaining() <= blockSize -> buffer.remaining()
            else -> blockSize
        }
    }
}
