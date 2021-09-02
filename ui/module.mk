# Makefile for the ui module

include $(MAKE_INCLUDE_DIR)/modules.mk

DOCKER_IMAGE_NAME := broadinstitute/workflow-launcher-$(MODULE)
$(IMAGES): $(MODULE_DIR)/Dockerfile $(MODULE_DIR)/.dockerignore
	$(CP) $(MODULE_DIR)/.dockerignore $(DERIVED_MODULE_DIR)
	$(DOCKER) build \
		--file $< \
		--tag $(DOCKER_IMAGE_NAME):latest \
		--tag $(DOCKER_IMAGE_NAME):$(WFL_VERSION) \
		$(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

$(CLEAN):
	-$(DOCKER) image rm -f $(DOCKER_IMAGE_NAME):$(WFL_VERSION)

$(DISTCLEAN):
	-$(DOCKER) image rm -f $(DOCKER_IMAGE_NAME):latest
