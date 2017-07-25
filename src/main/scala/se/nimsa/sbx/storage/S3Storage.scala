/*
 * Copyright 2014 Lars Edenbrandt
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

package se.nimsa.sbx.storage

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Exception
import akka.stream.alpakka.s3.auth.BasicCredentials
import akka.stream.alpakka.s3.scaladsl.S3Client
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.DeleteObjectsRequest
import com.amazonaws.{ClientConfiguration, Protocol}
import se.nimsa.sbx.lang.NotFoundException

import scala.concurrent.{ExecutionContext, Future}

/**
  * Service that stores DICOM files on AWS S3.
  *
  * @param s3Prefix prefix for keys
  * @param bucket   S3 bucket
  * @param region   aws region of the bucket
  */
class S3Storage(val bucket: String, val s3Prefix: String, val region: String)(implicit system: ActorSystem, materializer: Materializer) extends StorageService {

  // AWS credentials provider chain that looks for credentials in this order:
  // Environment Variables - AWS_ACCESS_KEY_ID and AWS_SECRET_KEY
  // Java System Properties - aws.accessKeyId and aws.secretKey
  // Instance profile credentials delivered through the Amazon EC2 metadata service
  val s3 = AmazonS3ClientBuilder.standard()
    .withRegion(region)
    .withCredentials(new DefaultAWSCredentialsProviderChain())
    .withClientConfiguration(new ClientConfiguration().withProtocol(Protocol.HTTPS))
    .build()

  private val s3Stream = S3Client(credentialsFromProviderChain(), region)

  private def credentialsFromProviderChain() = {
    val providerChain = new DefaultAWSCredentialsProviderChain()
    val creds = providerChain.getCredentials
    BasicCredentials(creds.getAWSAccessKeyId, creds.getAWSSecretKey)
  }

  private def s3Id(imageName: String): String = s3Prefix + "/" + imageName

  override def move(sourceImageName: String, targetImageName: String) = {
    s3.copyObject(bucket, sourceImageName, bucket, s3Id(targetImageName))
    s3.deleteObject(bucket, sourceImageName)
  }

  override def deleteByName(names: Seq[String]): Unit =
    if (names.length == 1)
      s3.deleteObject(bucket, s3Id(names.head))
    else
      s3.deleteObjects(new DeleteObjectsRequest(bucket).withKeys(names.map(name => s3Id(name)): _*).withQuiet(true))

  override def fileSink(name: String)(implicit executionContext: ExecutionContext): Sink[ByteString, Future[Done]] =
    s3Stream.multipartUpload(bucket, name).mapMaterializedValue(_.map(_ => Done))

  override def fileSource(imageId: Long): Source[ByteString, NotUsed] =
    s3Stream.download(bucket, s3Id(imageName(imageId))).mapError {
      // we do not have access to http status code here so not much we can do but map everything to NotFound
      case e: S3Exception => new NotFoundException(s"Data could not be transferred for image id $imageId: ${e.getMessage}")
    }

}
