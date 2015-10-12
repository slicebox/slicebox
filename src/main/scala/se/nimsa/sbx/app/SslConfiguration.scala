package se.nimsa.sbx.app

import java.security.{ SecureRandom, KeyStore }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }
import spray.io._

// for SSL support (if enabled in application.conf)
trait SslConfiguration {

  implicit def sslContext: SSLContext = {
    val keyStoreResource = "/keystore.jks"
    val password = "slicebox"

    val keyStore = KeyStore.getInstance("jks")
    keyStore.load(getClass.getResourceAsStream(keyStoreResource), password.toCharArray)
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, password.toCharArray)
    val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    trustManagerFactory.init(keyStore)
    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }

  implicit def sslEngineProvider: ServerSSLEngineProvider = {
    ServerSSLEngineProvider { engine =>
      engine.setEnabledProtocols(Array("SSLv3", "TLSv1"))
      engine
    }
  }
}  
