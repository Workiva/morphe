gen-docker:
	docker build \
	  --build-arg ARTIFACTORY_PRO_USER \
		--build-arg ARTIFACTORY_PRO_PASS \
		-f workivabuild.Dockerfile .
