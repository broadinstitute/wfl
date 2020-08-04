#!/usr/bin/make -f
# Project-Level Makefile for WFL

export PROJECT_DIR	:= $(CURDIR)
export DERIVED_DIR	:= $(PROJECT_DIR)/derived
MODULES	    := api

.PHONY: all $(MODULES) clean
all: $(MODULES)

$(MODULES):
	$+$(MAKE) --directory=$@ MODULE=$@ $(TARGET)

.PHONY: clean
clean:
	@echo $(RM) -r $(DERIVED_DIR)
