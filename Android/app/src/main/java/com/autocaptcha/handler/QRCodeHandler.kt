package com.autocaptcha.handler

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.autocaptcha.dataclass.PairedDeviceInfo

/** 处理 Win 端提供的二维码 */
class QRCodeHandler {
    private val aesKey = "autoCAPTCHA-encryptedKey" // 解密密钥

    /** 解密和验证二维码信息 */
    fun analyzeQRCode(qrData: String?): PairedDeviceInfo? {
        if (qrData.isNullOrBlank()) return null

        // 分割加密内容和签名
        val parts = qrData.split(".")
        if (parts.size != 2) return null

        val encryptedText = parts[0]
        val signature = parts[1]

        val decryptedText = decrypt(encryptedText) ?: return null
        val pairedDeviceInfo = Gson().fromJson(decryptedText, PairedDeviceInfo::class.java)

        val isValidSignature =
            verifySignature(encryptedText, signature, pairedDeviceInfo.windowsPublicKey)
        if (!isValidSignature) {
            Log.e("QRCodeHandler", "签名验证失败")
            return null
        }

        // 解密内容
        return pairedDeviceInfo
    }

    /** 解密二维码中的配对信息 */
    private fun decrypt(encrypted: String): String? {
        return try {
            val secretKey = SecretKeySpec(aesKey.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val ivSpec = IvParameterSpec(ByteArray(16)) // 全零向量
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decodedBytes = Base64.decode(encrypted, Base64.DEFAULT)
            val originalBytes = cipher.doFinal(decodedBytes)
            String(originalBytes)
        } catch (e: Exception) {
            Log.e("QRCodeHandler", "解密失败", e)
            null
        }
    }

    /** 验证二维码中的签名 */
    private fun verifySignature(data: String, signature: String, publicKey: String): Boolean {
        return try {
            // 解析公钥
            val keyBytes = Base64.decode(publicKey, Base64.DEFAULT)
            val spec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val pubKey = keyFactory.generatePublic(spec)

            // 初始化签名验证
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(pubKey)
            sig.update(data.toByteArray(Charsets.UTF_8))

            // 验证签名
            val signatureBytes = Base64.decode(signature, Base64.DEFAULT)
            val result = sig.verify(signatureBytes)

            if (!result) {
                Log.e(
                    "QRCodeHandler",
                    "签名验证失败: 数据 = $data, 签名 = $signature, 公钥 = $publicKey"
                )
            }

            result
        } catch (e: Exception) {
            Log.e("QRCodeHandler", "签名验证过程中出错", e)
            false
        }
    }
}