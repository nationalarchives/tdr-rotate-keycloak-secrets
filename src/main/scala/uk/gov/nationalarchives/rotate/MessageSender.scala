package uk.gov.nationalarchives.rotate
import io.circe.syntax._
import io.circe.generic.auto._
import com.typesafe.config.{Config, ConfigFactory}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.nationalarchives.rotate.MessageSender.RotationResult

import java.net.URI

class MessageSender(snsClient: SnsClient, topicArn: String) {

  def sendMessages(rotationResults: List[RotationResult]): Unit = {
    val publishRequest = PublishRequest.builder
      .message(rotationResults.asJson.noSpaces)
      .topicArn(topicArn)
      .build()
    snsClient.publish(publishRequest)
  }
}
object MessageSender {
  case class RotationNotification(results: List[RotationResult])
  case class RotationResult(success: Boolean, rotationResultErrorMessage: Option[String] = None)

  val config: Config = ConfigFactory.load
  val snsClient: SnsClient = SnsClient.builder()
    .region(Region.EU_WEST_2)
    .endpointOverride(URI.create(ConfigFactory.load.getString("sns.endpoint")))
    .build()
  def apply() = new MessageSender(snsClient, config.getString("sns.topic"))
}
