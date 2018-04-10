# Iago Web Example

Connect to a web server.

First, review the loadtest configuration, src/scripts/web-loadtest.sh.

## Build Iago

From the root of the Iago2 distribution

	mvn install -Dmaven.test.skip=true

## Build the example

	cd examples/web
	mvn package

## Run it

	sh -evx src/scripts/web-loadtest.sh
