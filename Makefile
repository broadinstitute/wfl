#!/usr/bin/make -f
# Top-level Makefile for workflow-launcher

export PROJECT_DIR      := $(CURDIR)
export DERIVED_DIR      := $(PROJECT_DIR)/derived
export MAKE_INCLUDE_DIR := $(PROJECT_DIR)/makerules

include $(MAKE_INCLUDE_DIR)/common.mk

# Enable for `WFL_VERSION` to be overriden
# Example:
# $ make WFL_VERSION=1.2.3
export WFL_VERSION ?= $(shell $(CAT) $(PROJECT_DIR)/version)

MODULES := api docs functions/aou helm ui

.PHONY: all $(MODULES)
all: $(MODULES)

$(MODULES):
	@+$(CD) $@ && $(MAKE) -f module.mk MODULE=$@ $(TARGET)

.PHONY: clean distclean
clean distclean:
	@+$(MAKE) TARGET=$@

.PHONY: help
help:
	@$(call brief-help, $(PROJECT_DIR)/Makefile)
