# Rotate Keycloak Secrets

This is the code for a lambda which will be run on a schedule using Cloudwatch events. 

It is used to rotate the client secrets for each of the confidential clients in Keycloak. It does this by:

* Rotating the client secret using the Keycloak Admin Client and getting the new value.
* Overwriting the existing value in the parameter store with the new value.
* Restarting the front end ECS service.

## Running Locally
There is a `LambdaRunner` class which you can run from IntelliJ or by using `sbt run`. 
You will need AWS credentials with permissions to get and put parameters for each of the Keycloak client secret parameters and to update the front end ECS service  
