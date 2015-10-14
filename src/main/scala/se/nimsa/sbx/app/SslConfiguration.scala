package se.nimsa.sbx.app

import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import spray.io.ServerSSLEngineProvider
import com.typesafe.config.ConfigFactory
import java.nio.file.Paths
import java.nio.file.Files

// for SSL support (if enabled in config)
trait SslConfiguration {

  implicit def sslContext: SSLContext = {
    try {
      val config = ConfigFactory.load()
      val keystorePath = config.getString("slicebox.ssl.keystore.path")
      val keystorePassword = config.getString("slicebox.ssl.keystore.password")

      val keyStore = KeyStore.getInstance("jks")
      keyStore.load(Files.newInputStream(Paths.get(keystorePath)), keystorePassword.toCharArray)
      val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore, keystorePassword.toCharArray)
      val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
      trustManagerFactory.init(keyStore)
      val context = SSLContext.getInstance("TLS")
      context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
      context
    } catch {
      case e: Exception => SSLContext.getDefault
    }
  }

  implicit def sslEngineProvider: ServerSSLEngineProvider = {
    ServerSSLEngineProvider { engine =>
      engine.setEnabledProtocols(Array("SSLv3", "TLSv1"))
      engine
    }
  }
}  
