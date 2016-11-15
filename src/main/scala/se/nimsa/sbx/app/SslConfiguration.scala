/*
 * Copyright 2016 Lars Edenbrandt
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
 */

package se.nimsa.sbx.app

import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

import com.typesafe.config.ConfigFactory
import java.nio.file.Paths
import java.nio.file.Files

import akka.http.scaladsl.ConnectionContext

// for SSL support (if enabled in config)
object SslConfiguration {

  def sslContext(): SSLContext =
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

  def httpsContext = ConnectionContext.https(sslContext())

}  
