# Makefile for the wfl/api module

include $(MAKE_INCLUDE_DIR)/modules.mk

CLASSES_DIR           := $(MODULE_DIR)/classes
CPCACHE_DIR           := $(MODULE_DIR)/.cpcache
RESOURCES_DIR         := $(MODULE_DIR)/resources
SRC_DIR               := $(MODULE_DIR)/src
TEST_DIR              := $(MODULE_DIR)/test
TEST_RESOURCES_DIR    := $(TEST_DIR)/resources
DERIVED_RESOURCES_DIR := $(DERIVED_MODULE_DIR)/resources
DERIVED_SRC_DIR       := $(DERIVED_MODULE_DIR)/src
DERIVED_TARGET_DIR    := $(DERIVED_MODULE_DIR)/target
DERIVED_TEST_DIR      := $(DERIVED_MODULE_DIR)/test
CLOJURE_PROJECT       := $(MODULE_DIR)/deps.edn

API_DIR      := $(CLASSES_DIR)/wfl/api
POM_IN       := $(MODULE_DIR)/pom.xml
POM_OUT      := $(SRC_DIR)/META-INF/maven/org.broadinstitute/wfl/pom.xml

CLEAN_DIRS  += $(CLASSES_DIR) $(CPCACHE_DIR) $(SRC_DIR)/META-INF
CLEAN_FILES += $(POM_IN) $(POM_OUT)

SCM_RESOURCES      = $(shell $(FIND) $(RESOURCES_DIR) -type f)
SCM_SRC            = $(shell $(FIND) $(SRC_DIR) -type f -name "*.$(CLJ)")
DERIVED_RESOURCES  = $(shell $(FIND) $(DERIVED_RESOURCES_DIR) -type f -name "*.$(CLJ)")
DERIVED_SRC        = $(shell $(FIND) $(DERIVED_SRC_DIR) -type f -name "*.$(CLJ)")
TEST_SCM_RESOURCES = $(shell $(FIND) $(TEST_RESOURCES_DIR) -type f)
TEST_SCM_SRC       = $(shell $(FIND) $(TEST_DIR) -type f -name "*.$(CLJ)")

JAR          := $(DERIVED_TARGET_DIR)/wfl-$(WFL_VERSION).jar
JAR_LINK     := $(DERIVED_TARGET_DIR)/wfl.jar

$(PREBUILD): $(MODULE_DIR)/build/build.$(CLJ)
$(PREBUILD): $(SCM_RESOURCES) $(TEST_SCM_RESOURCES)
	@$(MKDIR) $(DERIVED_RESOURCES_DIR) $(DERIVED_SRC_DIR) $(DERIVED_TEST_DIR) $(CLASSES_DIR)
	$(CLOJURE) -M -e "(compile 'wfl.util)"
	$(CLOJURE) -X:prebuild
	@$(TOUCH) $@

$(POM_IN): $(CLOJURE_PROJECT)
	$(CLOJURE) -Spom

$(POM_OUT): $(POM_IN) $(PREBUILD)
	$(CLOJURE) -X:update-the-pom :in '"$(POM_IN)"' :out '"$@"'

$(BUILD): $(SCM_SRC) $(POM_OUT)
	@$(MKDIR) $(CLASSES_DIR) $(DERIVED_TARGET_DIR)
	$(CLOJURE) -M -e "(compile 'wfl.main)"
	$(CLOJURE) -M:uberjar -m uberdeps.uberjar \
		--level error --multi-release --main-class wfl.main \
		--target $(JAR)
	$(LN) $(JAR) $(JAR_LINK)
	@$(TOUCH) $@

$(LINT): $(SCM_SRC) $(SCM_RESOURCES)
	$(CLOJURE) -M:check-format
	$(CLOJURE) -M:eastwood
	$(CLOJURE) -M:kibit
	-$(CLOJURE) -M:kondo --config ./resources/kondo.edn --lint .
	@$(TOUCH) $@

$(UNIT): $(TEST_SCM_SRC)
	$(EXPORT) CPCACHE=$(CPCACHE_DIR);     \
	$(CLOJURE) $(CLJFLAGS) -M:test unit | \
	$(TEE) $(DERIVED_MODULE_DIR)/unit.log
	@$(TOUCH) $@

$(INTEGRATION): $(TEST_SCM_SRC)
	$(EXPORT) CPCACHE=$(CPCACHE_DIR);            \
	$(CLOJURE) $(CLJFLAGS) -M:test integration | \
	$(TEE) $(DERIVED_MODULE_DIR)/integration.log
	@$(TOUCH) $@

$(SYSTEM): $(TEST_SCM_SRC)
	$(EXPORT) CPCACHE=$(CPCACHE_DIR);            \
	$(EXPORT) WFL_WFL_URL=http://localhost:3000; \
	$(CLOJURE) $(CLJFLAGS) -M:parallel-test wfl.system.v1-endpoint-test | \
	$(TEE) $(DERIVED_MODULE_DIR)/system.log
	@$(TOUCH) $@

DOCKER_IMAGE_NAME := broadinstitute/workflow-launcher-$(MODULE)
$(IMAGES): $(MODULE_DIR)/Dockerfile $(MODULE_DIR)/.dockerignore
	$(DOCKER) build \
		--file $< \
		--tag $(DOCKER_IMAGE_NAME):latest \
		--tag $(DOCKER_IMAGE_NAME):$(WFL_VERSION) \
		$(PROJECT_DIR)
	@$(TOUCH) $@

$(CLEAN):
	-$(DOCKER) image rm -f $(DOCKER_IMAGE_NAME):$(WFL_VERSION)

$(DISTCLEAN):
	-$(DOCKER) image rm -f $(DOCKER_IMAGE_NAME):latest
