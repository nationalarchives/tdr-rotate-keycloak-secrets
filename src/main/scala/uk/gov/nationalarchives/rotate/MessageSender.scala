package uk.gov.nationalarchives.rotate
import io.circe.syntax._
import io.circe.generic.auto._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.nationalarchives.rotate.MessageSender.{RotationNotification, RotationResult}
import uk.gov.nationalarchives.rotate.ApplicationConfig._

import java.net.URI

class MessageSender(snsClient: SnsClient, topicArn: String) {

  def sendMessages(rotationResults: List[RotationResult]): Unit = {
    val publishRequest = PublishRequest.builder
      .message(RotationNotification(rotationResults).asJson.noSpaces)
      .topicArn(topicArn)
      .build()
    snsClient.publish(publishRequest)
  }
}
object MessageSender {
  case class RotationNotification(results: List[RotationResult])
  case class RotationResult(clientId: String, success: Boolean, rotationResultErrorMessage: Option[String] = None)

  val snsClient: SnsClient = SnsClient.builder()
    .region(Region.EU_WEST_2)
    .endpointOverride(URI.create(snsEndpoint))
    .build()
  def apply() = new MessageSender(snsClient, snsTopic)
}
