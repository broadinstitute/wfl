# Makefile for the ui module

include $(MAKE_INCLUDE_DIR)/common.mk
include $(MAKE_INCLUDE_DIR)/Makefile.module

SRC_DIR	:= $(MODULE_DIR)/src
SCM_SRC := \
	$(shell $(FIND) $(MODULE_DIR)/public -type f) \
	$(shell $(FIND) $(MODULE_DIR)/src -type f)

$(PREBUILD): $(MODULE_DIR)/package.json
	$(CP) $< $(DERIVED_MODULE_DIR)
	$(NPM) install --prefix $(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

$(BUILD): $(SCM_SRC)
	$(RM) -r $(DERIVED_MODULE_DIR)/public $(DERIVED_MODULE_DIR)/src
	$(CP) -r $(MODULE_DIR)/* $(DERIVED_MODULE_DIR)
	$(NPM) run build --prefix $(DERIVED_MODULE_DIR)
	@$(TOUCH) $@

$(IMAGES): $(MODULE_DIR)/Dockerfile
	$(DOCKER) build                                                       \
		--file $<                                                         \
		--tag "broadinstitute/workflow-launcher-$(MODULE):$(WFL_VERSION)" \
		$(DERIVED_MODULE_DIR)
	@$(TOUCH) $@
