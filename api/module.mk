# Makefile for the wfl/api module

REQUIRED_2P_REPOSITORIES := dsde-pipelines pipeline-config
include $(MAKE_INCLUDE_DIR)/Makefile.module


CPCACHE_DIR           := $(MODULE_DIR)/.cpcache
RESOURCES_DIR         := $(MODULE_DIR)/resources
SRC_DIR               := $(MODULE_DIR)/src
DERIVED_RESOURCES_DIR := $(DERIVED_MODULE_DIR)/resources
DERIVED_SRC_DIR		  := $(DERIVED_MODULE_DIR)/src
DERIVED_TARGET_DIR    := $(DERIVED_MODULE_DIR)/target
BOOT_PROJECT          := $(MODULE_DIR)/build.boot
CLOJURE_PROJECT       := $(MODULE_DIR)/deps.edn
LEIN_PROJECT          := $(MODULE_DIR)/project.clj

CLEAN_DIRS  += $(CPCACHE_DIR)
CLEAN_FILES += $(LEIN_PROJECT) $(MODULE_DIR)/wfl

SCM_SRC       = $(shell $(FIND) $(SRC_DIR) -type f -name "*.$(CLJ)")
SCM_RESOURCES = $(shell $(FIND) $(RESOURCES_DIR) -type f)
JAR          := $(DERIVED_TARGET_DIR)/wfl-$(WFL_VERSION).jar
JAR_LINK     := $(DERIVED_TARGET_DIR)/wfl.jar

$(PREBUILD): $(MODULE_DIR)/wfl
	@$(MKDIR) $(DERIVED_RESOURCES_DIR) $(DERIVED_SRC_DIR)
	$(BOOT) prebuild
	@$(TOUCH) $@

$(MODULE_DIR)/wfl:
	$(LN) $(BOOT_PROJECT) $@

$(BUILD): $(SCM_SRC) $(SCM_RESOURCES)
	@$(MKDIR) $(DERIVED_TARGET_DIR)
	$(BOOT) build
	$(LN) $(JAR) $(JAR_LINK)
	@$(TOUCH) $@

LOGFILE := $(DERIVED_MODULE_DIR)/test.log
$(CHECK): $(SCM_SRC) $(SCM_RESOURCES) $(CLOJURE_PROJECT)
	$(EXPORT) CPCACHE=$(CPCACHE_DIR);                        \
	$(CLOJURE) $(CLJFLAGS) -A:test unit | $(TEE) $(LOGFILE)
	@$(TOUCH) $@

DOCKER_API_IMAGE := broadinstitute/workflow-launcher-$(MODULE):$(WFL_VERSION)
$(IMAGES): $(MODULE_DIR)/Dockerfile
	$(DOCKER) build --file $< --tag $(DOCKER_API_IMAGE) $(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

$(CLEAN):
	-$(DOCKER) image rm -f $(DOCKER_API_IMAGE)
