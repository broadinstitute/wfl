#!/usr/bin/make -f
# Project-Level Makefile for WFL

export PROJECT_DIR	:= $(CURDIR)
export DERIVED_DIR	:= $(PROJECT_DIR)/derived
export WFL_VERSION  := $(shell cat $(PROJECT_DIR)/version)

MODULES	    := api

.PHONY: all $(MODULES) clean
all: $(MODULES)

$(MODULES):
	$+$(MAKE) --directory=$@ MODULE=$@ $(TARGET)

.PHONY: clean
clean:
	@echo $(RM) -r $(DERIVED_DIR)
