package uk.gov.nationalarchives.rotate

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.http.RequestMethod._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import org.keycloak.admin.client.{Keycloak, KeycloakBuilder}
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, EitherValues}

import scala.jdk.CollectionConverters._

class RotateRealmKeysSpec  extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterEach with BeforeAndAfterAll with EitherValues {
  case class Component(id: Option[String], name: String = "rsa-generated", providerId: String = "rsa-generated", providerType: String = "org.keycloak.keys.KeyProvider", parentId: String = "tdr", config: Map[String, List[String]])
  def config(active: Boolean): Map[String, List[String]] = Map("keySize" -> List("2048"), "active" -> List(active.toString), "priority" -> List("100"), "enabled" -> List("true"), "algorithm" -> List("RS256"))

  val initiallyActiveId = "967d41bc-e719-4461-ad44-3ddc3d01e7a5"
  val initiallyInactiveId = "b0bd9d1d-65c3-4fdc-bb20-2016cab15e01"

  val authServer = new WireMockServer(1081)
  val componentsResponseJson: String = List(
    Component(Option(initiallyActiveId), config = config(true)),
    Component(Option(initiallyInactiveId), config = config(false))
  ).asJson.noSpaces

  override def beforeAll(): Unit = {
    super.beforeAll()
    authServer.start()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    authServer.resetAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    authServer.stop()
  }

  def stubRequests(): StubMapping = {
    authServer
      .stubFor(get(urlEqualTo("/admin/realms/tdr/components?parent=tdr&type=org.keycloak.keys.KeyProvider&name=rsa-generated"))
        .willReturn(ok(componentsResponseJson)
          .withHeader("Content-Type", "application/json"))
      )
    authServer.stubFor(post(urlEqualTo("/admin/realms/tdr/components"))
      .willReturn(noContent().withHeader("Content-Type", "application/json"))
    )
    authServer.stubFor(put(urlEqualTo("/admin/realms/tdr/components/967d41bc-e719-4461-ad44-3ddc3d01e7a5"))
      .willReturn(noContent().withHeader("Content-Type", "application/json"))
    )
    authServer.stubFor(delete(urlEqualTo("/admin/realms/tdr/components/b0bd9d1d-65c3-4fdc-bb20-2016cab15e01"))
      .willReturn(noContent().withHeader("Content-Type", "application/json"))
    )
  }

  val client: Keycloak = KeycloakBuilder.builder()
    .serverUrl("http://localhost:1081")
    .realm("tdr")
    .authorization("token")
    .build()

  def loggedRequest(method: RequestMethod): LoggedRequest =
    authServer.getAllServeEvents.asScala
      .find(_.getRequest.getMethod == method)
      .map(_.getRequest)
      .get


  "The rotate method" should "should invalidate the active key" in {
    stubRequests()
    new RotateRealmKeys(client).rotate()
    val component: Component = decode[Component](loggedRequest(PUT).getBodyAsString).right.value
    component.id.get should equal(initiallyActiveId)
  }

  "The rotate method" should "delete the passive key" in {
    stubRequests()
    new RotateRealmKeys(client).rotate()
    loggedRequest(DELETE).getUrl.endsWith(initiallyInactiveId)
  }

  "The rotate method" should "create a new key" in {
    stubRequests()
    new RotateRealmKeys(client).rotate()
    val component: Component = decode[Component](loggedRequest(POST).getBodyAsString).right.value
    component.id.isDefined should be(false)
    component.config("active").head.toBoolean should be(true)
  }

  "The rotate method" should "return a success message if the rotation completes successfully" in {
    stubRequests()
    val response = new RotateRealmKeys(client).rotate()
    response.message should equal("Realm key rotation for the TDR realm completed successfully")
  }

  "The rotate method" should "return a failure message if there is an error" in {
    val response = new RotateRealmKeys(client).rotate()
    response.message should equal("Realm key rotation for the TDR realm has failed HTTP 404 Not Found")
  }
}
