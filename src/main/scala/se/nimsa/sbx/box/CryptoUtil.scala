package se.nimsa.sbx.box

import javax.crypto.KeyGenerator
import javax.crypto.Cipher
import java.io.ByteArrayOutputStream
import javax.crypto.CipherOutputStream
import java.security.Key
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

object CryptoUtil {

  val algorithm = "AES"
  val transformation = "AES/CBC/PKCS5PADDING"
  
  def createBase64EncodedKey() = {
    val keyGen = KeyGenerator.getInstance("AES");
    keyGen.init(256)
    val aesKey = keyGen.generateKey();
    Base64.getEncoder().encodeToString(aesKey.getEncoded());
  }

  def createCipher(keyBase64: String, ivKeyBase64: String): (Cipher, SecretKeySpec, IvParameterSpec)  = {
    val key = Base64.getDecoder.decode(keyBase64)
    val ivKey = Base64.getDecoder.decode(ivKeyBase64)
    val iv = new IvParameterSpec(ivKey)
    val skeySpec = new SecretKeySpec(key, algorithm)
    (Cipher.getInstance(transformation), skeySpec, iv)
  }
  
  def encrypt(bytes: Array[Byte], keyBase64: String, ivKeyBase64: String): Array[Byte] = {
    val (cipher, skeySpec, iv) = createCipher(keyBase64, ivKeyBase64)
    cipher.init(Cipher.ENCRYPT_MODE, skeySpec, iv)
    cipher.doFinal(bytes)
  }

  def decrypt(bytes: Array[Byte], keyBase64: String, ivKeyBase64: String) = {
    val (cipher, skeySpec, iv) = createCipher(keyBase64, ivKeyBase64)
    cipher.init(Cipher.DECRYPT_MODE, skeySpec, iv)
    cipher.doFinal(bytes)
  }

}