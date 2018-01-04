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

import java.io.ByteArrayInputStream

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Exception
import akka.stream.alpakka.s3.scaladsl.S3Client
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.{CopyObjectRequest, DeleteObjectsRequest, ObjectMetadata, PutObjectRequest}
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
  private val s3 = AmazonS3ClientBuilder.standard()
    .withRegion(region)
    .withCredentials(new DefaultAWSCredentialsProviderChain())
    .withClientConfiguration(new ClientConfiguration().withProtocol(Protocol.HTTPS))
    .build()

  private def s3Id(imageName: String): String = s3Prefix + "/" + imageName

  override def move(sourceImageName: String, targetImageName: String): Unit = {
    val request = new CopyObjectRequest(bucket, sourceImageName, bucket, s3Id(targetImageName))
    val metadata = new ObjectMetadata()
    metadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION)
    request.setNewObjectMetadata(metadata)
    s3.copyObject(request)
    s3.deleteObject(bucket, sourceImageName)
  }

  override def deleteByName(names: Seq[String]): Unit =
    if (names.lengthCompare(1) == 0)
      s3.deleteObject(bucket, s3Id(names.head))
    else {
      // micro-batch this since S3 accepts up to 1000 deletes at a time
      names.grouped(1000).map { subset =>
        s3.deleteObjects(new DeleteObjectsRequest(bucket).withKeys(subset.map(name => s3Id(name)): _*).withQuiet(true))
      }
    }

  override def fileSink(name: String)(implicit executionContext: ExecutionContext): Sink[ByteString, Future[Done]] =
    Sink
      .fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
      .mapMaterializedValue(_.map { bytes =>
        val metadata: ObjectMetadata = new ObjectMetadata
        metadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION)
        metadata.setContentLength(bytes.length)
        metadata.setContentType("application/octet-stream")
        val putObjectRequest: PutObjectRequest = new PutObjectRequest(bucket, name, new ByteArrayInputStream(bytes.toArray), metadata)
        s3.putObject(putObjectRequest)
        Done
      })

  override def fileSource(name: String): Source[ByteString, NotUsed] =
    S3Client(new DefaultAWSCredentialsProviderChain(), region).download(bucket, s3Id(name)).mapError {
      // we do not have access to http status code here so not much we can do but map everything to NotFound
      case e: S3Exception => new NotFoundException(s"Data could not be transferred for name $name: ${e.getMessage}")
    }

}
