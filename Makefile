gen-docker:
	docker build -f workivabuild.Dockerfile .

update-tocs:
	./.circleci/scripts/update-tocs.sh

github-pages:
	bundle exec jekyll serve
	open http://localhost:4000
