# Makefile for the wfl/api module

REQUIRED_GITHUB_REPOSITORIES := \
	broadinstitute/dsde-pipelines \
	broadinstitute/pipeline-config

include $(MAKE_INCLUDE_DIR)/common.mk
include $(MAKE_INCLUDE_DIR)/Makefile.module

# Makefile for API
# clojure tools ignore the CPCACHE environment variable
CPCACHE_DIR           := $(MODULE_DIR)/.cpcache
SRC_DIR	              := $(MODULE_DIR)/src
DERIVED_RESOURCES_DIR := $(DERIVED_MODULE_DIR)/resources
DERIVED_TARGET_DIR    := $(DERIVED_MODULE_DIR)/target

CLEAN_DIRS  += $(CPCACHE_DIR)

RESOURCES   := $(DERIVED_RESOURCES_DIR).$(TS)
SCM_SRC      = $(shell $(FIND) $(SRC_DIR) -name "*.$(CLJ)")
ARTIFACT    := $(DERIVED_TARGET_DIR)/wfl-$(WFL_VERSION).jar
SYMLINK     := $(DERIVED_TARGET_DIR)/wfl.jar

$(PREBUILD):
	@$(MKDIR) $(DERIVED_RESOURCES_DIR)
	$(BOOT) prebuild
	@$(TOUCH) $@

$(BUILD): $(ARTIFACT) $(SYMLINK)
$(ARTIFACT): $(SCM_SRC)
	@$(MKDIR) $(DERIVED_TARGET_DIR)
	$(BOOT) build

$(SYMLINK): $(ARTIFACT)
	$(LN) $< $@

$(CHECK): $(SCM_SRC)
	$(EXPORT) CPCACHE=$(CPCACHE_DIR) && $(CLOJURE) $(CLJFLAGS) -A:test unit
	@$(TOUCH) $@

$(IMAGES): $(MODULE_DIR)/Dockerfile $(ARTIFACT)
	$(DOCKER) build                                                       \
		--file $<                                                         \
		--tag "broadinstitute/workflow-launcher-$(MODULE):$(WFL_VERSION)" \
		$(DERIVED_MODULE_DIR)
	@$(TOUCH) $@
