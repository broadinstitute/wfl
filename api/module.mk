# Makefile for the wfl/api module

REQUIRED_2P_REPOSITORIES := pipeline-config warp
include $(MAKE_INCLUDE_DIR)/modules.mk

CPCACHE_DIR           := $(MODULE_DIR)/.cpcache
RESOURCES_DIR         := $(MODULE_DIR)/resources
SRC_DIR               := $(MODULE_DIR)/src
TEST_DIR              := $(MODULE_DIR)/test
TEST_RESOURCES_DIR    := $(TEST_DIR)/resources
DERIVED_RESOURCES_DIR := $(DERIVED_MODULE_DIR)/resources
DERIVED_SRC_DIR		  := $(DERIVED_MODULE_DIR)/src
DERIVED_TARGET_DIR    := $(DERIVED_MODULE_DIR)/target
CLOJURE_PROJECT       := $(MODULE_DIR)/deps.edn

CLEAN_DIRS  += $(CPCACHE_DIR)
CLEAN_FILES += $(LEIN_PROJECT) $(MODULE_DIR)/wfl

SCM_RESOURCES      = $(shell $(FIND) $(RESOURCES_DIR) -type f)
SCM_SRC            = $(shell $(FIND) $(SRC_DIR) -type f -name "*.$(CLJ)")
DERIVED_RESOURCES  = $(shell $(FIND) $(DERIVED_RESOURCES_DIR) -type f -name "*.$(CLJ)")
DERIVED_SRC        = $(shell $(FIND) $(DERIVED_SRC_DIR) -type f -name "*.$(CLJ)")
TEST_SCM_RESOURCES = $(shell $(FIND) $(TEST_RESOURCES_DIR) -type f)
TEST_SCM_SRC       = $(shell $(FIND) $(TEST_DIR) -type f -name "*.$(CLJ)")

JAR          := $(DERIVED_TARGET_DIR)/wfl-$(WFL_VERSION).jar
JAR_LINK     := $(DERIVED_TARGET_DIR)/wfl.jar

$(PREBUILD):
	@$(MKDIR) $(DERIVED_RESOURCES_DIR) $(DERIVED_SRC_DIR)
	clojure -X wfl.boot/prebuild
	@$(TOUCH) $@

$(BUILD): $(SCM_SRC) $(SCM_RESOURCES)
	@$(MKDIR) $(DERIVED_TARGET_DIR)
	$(BOOT) build
	$(LN) $(JAR) $(JAR_LINK)
	@$(TOUCH) $@

$(LINT): $(SCM_SRC) $(SCM_RESOURCES)
	@$(CLOJURE) -A:lint
	@$(TOUCH) $@

$(UNIT): $(TEST_SCM_SRC) $(TEST_SCM_RESOURCES) $(CLOJURE_PROJECT)
	$(EXPORT) CPCACHE=$(CPCACHE_DIR);     \
	$(CLOJURE) $(CLJFLAGS) -A:test unit | \
	$(TEE) $(DERIVED_MODULE_DIR)/unit.log
	@$(TOUCH) $@

$(INTEGRATION): $(TEST_SCM_SRC) $(TEST_SCM_RESOURCES) $(CLOJURE_PROJECT)
	$(EXPORT) CPCACHE=$(CPCACHE_DIR);            \
	$(CLOJURE) $(CLJFLAGS) -A:test integration | \
	$(TEE) $(DERIVED_MODULE_DIR)/integration.log
	@$(TOUCH) $@

DOCKER_API_IMAGE := broadinstitute/workflow-launcher-$(MODULE):$(WFL_VERSION)
$(IMAGES): $(MODULE_DIR)/Dockerfile $(MODULE_DIR)/.dockerignore
	$(CP) $(MODULE_DIR)/.dockerignore $(DERIVED_MODULE_DIR)
	$(DOCKER) build --file $< --tag $(DOCKER_API_IMAGE) $(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

$(CLEAN):
	-$(DOCKER) image rm -f $(DOCKER_API_IMAGE)
