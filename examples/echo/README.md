# Iago Echo Example

This is the README for an example usage of Iago. This brings up an echo server on your localhost,
then creates an Iago job to send traffic to your server.

If you are unfamiliar with how to use Iago, check out src & echo-loadtest.sh. Also read the Iago2 documentation.

## Build Iago

mvn install -Dmaven.test.skip=true

## Build the example

cd examples/echo
mvn package

## Start the Echo Server

sh -evx src/scripts/echo-server.sh

## Run Iago

sh -evx src/scripts/echo-loadtest.sh
