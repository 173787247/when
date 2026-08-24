SHELL := /usr/bin/env bash

VERSION ?= $(shell git describe --tags --always --dirty)
DIST_DIR := $(CURDIR)/dist
IMAGE_REPO ?= whenproject/when

.PHONY: help clean clean-dist verify server admin-web release release-server release-web \
	check-release image k8s-validate

help:
	@printf '%s\n' \
		'make verify' \
		'make release VERSION=1.0.0' \
		'make image VERSION=1.0.0 IMAGE_REPO=whenproject/when' \
		'make k8s-validate'

clean:
	./mvnw -q clean
	$(MAKE) clean-dist

clean-dist:
	rm -rf -- "$(CURDIR)/dist"

verify:
	./mvnw -B -ntp verify

server:
	./mvnw -B -ntp -pl when-app -am package -DskipTests

admin-web:
	./build/build-web.sh

release-server: server
	./build/package-server.sh "$(VERSION)" "$(DIST_DIR)"

release-web: admin-web
	./build/package-web.sh "$(VERSION)" "$(DIST_DIR)"

check-release:
	./build/check-release.sh "$(VERSION)" "$(DIST_DIR)"

release: verify
	$(MAKE) clean-dist
	$(MAKE) release-server VERSION="$(VERSION)"
	$(MAKE) release-web VERSION="$(VERSION)"
	$(MAKE) check-release VERSION="$(VERSION)"

image: server
	docker build --build-arg VERSION="$(VERSION)" -t "$(IMAGE_REPO):$(VERSION)" .

k8s-validate:
	./build/validate-k8s.sh
