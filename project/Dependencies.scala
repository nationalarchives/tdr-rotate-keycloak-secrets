import sbt._

object Dependencies {
  private val circeVersion = "0.14.6"

  lazy val awsSns = "software.amazon.awssdk" % "sns" % "2.24.13"
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % "2.24.13"
  lazy val awsEcs = "software.amazon.awssdk" % "ecs" % "2.24.13"
  lazy val circeCore = "io.circe" %% "circe-core" % circeVersion
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  lazy val circeParser = "io.circe" %% "circe-parser" % circeVersion
  lazy val keycloakAdminClient = "org.keycloak" % "keycloak-admin-client" % "23.0.7"
  lazy val lambdaCore = "com.amazonaws" % "aws-lambda-java-core" % "1.2.3"
  lazy val mockito = "org.mockito" %% "mockito-scala" % "1.17.30"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.18"
  lazy val slf4j = "org.slf4j" % "slf4j-simple" % "2.0.12"
  lazy val typesafe = "com.typesafe" % "config" % "1.4.3"
  lazy val wiremock = "com.github.tomakehurst" % "wiremock" % "3.0.1"
}
