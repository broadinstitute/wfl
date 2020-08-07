# Makefile for the wfl/api module

REQUIRED_2P_REPOSITORIES := dsde-pipelines pipeline-config
include $(MAKE_INCLUDE_DIR)/Makefile.module


CPCACHE_DIR           := $(MODULE_DIR)/.cpcache
SRC_DIR	              := $(MODULE_DIR)/src
DERIVED_RESOURCES_DIR := $(DERIVED_MODULE_DIR)/resources
DERIVED_TARGET_DIR    := $(DERIVED_MODULE_DIR)/target

CLEAN_DIRS  += $(CPCACHE_DIR)
CLEAN_FILES += $(MODULE_DIR)/project.clj $(MODULE_DIR)/wfl

RESOURCES   := $(DERIVED_RESOURCES_DIR).$(TS)
SCM_SRC      = $(shell $(FIND) $(SRC_DIR) -name "*.$(CLJ)")
ARTIFACT    := $(DERIVED_TARGET_DIR)/wfl-$(WFL_VERSION).jar
SYMLINK     := $(DERIVED_TARGET_DIR)/wfl.jar

$(PREBUILD):
	@$(MKDIR) $(DERIVED_RESOURCES_DIR)
	$(BOOT) prebuild
	@$(TOUCH) $@

$(BUILD): $(SCM_SRC)
	@$(MKDIR) $(DERIVED_TARGET_DIR)
	$(BOOT) build
	$(LN) $(ARTIFACT) $(SYMLINK)
	@$(TOUCH) $@

LOGFILE := $(DERIVED_MODULE_DIR)/test.log
$(CHECK): $(SCM_SRC)
	$(EXPORT) CPCACHE=$(CPCACHE_DIR);                        \
	$(CLOJURE) $(CLJFLAGS) -A:test unit | $(TEE) $(LOGFILE);
	@$(TOUCH) $@

DOCKER_API_IMAGE := broadinstitute/workflow-launcher-$(MODULE):$(WFL_VERSION)
$(IMAGES): $(MODULE_DIR)/Dockerfile
	$(DOCKER) build --file $< --tag $(DOCKER_API_IMAGE) $(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

$(CLEAN):
	-$(DOCKER) image rm -f $(DOCKER_API_IMAGE)
