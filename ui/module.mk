# Makefile for the ui module

include $(MAKE_INCLUDE_DIR)/Makefile.module

PUBLIC_DIR := $(MODULE_DIR)/public
SRC_DIR    := $(MODULE_DIR)/src
SCM_SRC     = \
	$(shell $(FIND) $(PUBLIC_DIR) $(SRC_DIR) -type f) \
	$(MODULE_DIR)/vue.config.js \
	$(MODULE_DIR)/babel.config.js

DERIVED_NPM_CONFIG := \
    $(DERIVED_MODULE_DIR)/package.json \
	$(DERIVED_MODULE_DIR)/package-lock.json

DERIVED_SRC = $(patsubst $(MODULE_DIR)%,$(DERIVED_MODULE_DIR)%,$(SCM_SRC))

$(PREBUILD): $(DERIVED_NPM_CONFIG)
	$(NPM) install --prefix $(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

$(LINT): $(DERIVED_SRC)
	$(NPM) run lint --prefix $(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

$(BUILD): $(DERIVED_SRC)
	$(NPM) run build --prefix $(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

DOCKER_UI_IMAGE := broadinstitute/workflow-launcher-$(MODULE):$(WFL_VERSION)
$(IMAGES): $(MODULE_DIR)/Dockerfile $(MODULE_DIR)/Dockerfile.dockerignore
	$(DOCKER) build --file $< --tag $(DOCKER_UI_IMAGE) $(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

$(CLEAN):
	-$(DOCKER) image rm -f $(DOCKER_UI_IMAGE)

$(DERIVED_NPM_CONFIG): $(DERIVED_MODULE_DIR)/%.json : $(MODULE_DIR)/%.json
	$(SED) "s/%VERSION%/$(WFL_VERSION)/g" $< > $@

DERIVED_SRC_DIRS = $(filter-out $(DERIVED_MODULE_DIR),\
	$(shell eval "$(DIRNAME) $(DERIVED_SRC) | $(SORT) | $(UNIQUE)"))

$(DERIVED_SRC): | $(DERIVED_SRC_DIRS)
$(DERIVED_SRC): $(DERIVED_MODULE_DIR)/% : $(MODULE_DIR)/%
	$(CP) $< $@

$(DERIVED_SRC_DIRS):
	@$(MKDIR) $@
