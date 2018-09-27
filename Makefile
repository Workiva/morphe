gen-docker:
	docker build \
	  --build-arg ARTIFACTORY_USER \
		--build-arg ARTIFACTORY_PASS \
		-f workivabuild.Dockerfile .
