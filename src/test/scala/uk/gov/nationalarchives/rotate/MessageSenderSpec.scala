package uk.gov.nationalarchives.rotate

import org.mockito.ArgumentMatchers.any
import org.mockito.{ArgumentCaptor, Mockito, MockitoSugar}
import io.circe.syntax._
import io.circe.generic.auto._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.{PublishRequest, PublishResponse}
import uk.gov.nationalarchives.rotate.MessageSender.RotationResult


class MessageSenderSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "sendMessages" should "send a message to the correct topic" in {
    val snsMock = Mockito.mock(classOf[SnsClient])
    val topicArn = "arn:aws:sns:region:account:name"

    val argumentCaptor: ArgumentCaptor[PublishRequest] = ArgumentCaptor.forClass(classOf[PublishRequest])
    doAnswer(PublishResponse.builder.build).when(snsMock).publish(argumentCaptor.capture())

    val rotationResults = List(RotationResult(success = true))
    new MessageSender(snsMock, topicArn).sendMessages(rotationResults)

    val resultTopic = argumentCaptor.getValue.topicArn()
    resultTopic should equal(topicArn)
    verify(snsMock).publish(any[PublishRequest])
    reset(snsMock)
  }

  "sendMessages" should "send a message with the correct json" in {
    val snsMock = Mockito.mock(classOf[SnsClient])
    val topicArn = "arn:aws:sns:region:account:name"

    val argumentCaptor: ArgumentCaptor[PublishRequest] = ArgumentCaptor.forClass(classOf[PublishRequest])
    doAnswer(PublishResponse.builder.build).when(snsMock).publish(argumentCaptor.capture())

    val rotationResults = List(RotationResult(success = true), RotationResult(success = false, Option("An error message")))
    new MessageSender(snsMock, topicArn).sendMessages(rotationResults)

    val message = argumentCaptor.getValue.message()

    verify(snsMock).publish(argumentCaptor.capture())
    message should equal(rotationResults.asJson.noSpaces)
  }
}
