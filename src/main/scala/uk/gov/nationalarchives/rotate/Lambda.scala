package uk.gov.nationalarchives.rotate

import com.amazonaws.services.lambda.runtime.Context
import org.keycloak.admin.client.Keycloak

import java.io.{InputStream, OutputStream}
import scala.annotation.unused

class Lambda() {
  val messageSender: MessageSender = MessageSender()
  val client: Keycloak = KeycloakAdminClient().client
  val rotateClientSecrets: RotateClientSecrets = RotateClientSecrets(client)
  val rotateRealmKeys: RotateRealmKeys = RotateRealmKeys(client)

  def handleRequest(@unused input: InputStream, @unused output: OutputStream, @unused context: Context): Unit = {
    messageSender.sendMessages(rotateClientSecrets.rotate())
    messageSender.sendMessages(rotateRealmKeys.rotate() :: Nil)
  }
}
