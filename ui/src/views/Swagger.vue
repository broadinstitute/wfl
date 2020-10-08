<template>
  <div id="swagger-ui"></div>
</template>

<script>
import axios from "axios";
import SwaggerUI from "swagger-ui";
import "swagger-ui/dist/swagger-ui.css";

export default {
  name: "swagger-ui",
  mounted: function () {
    const swagger = SwaggerUI({
      dom_id: "#swagger-ui",
      url: "/swagger/swagger.json",
    });

    axios.get("/oauth2id").then((response) => {
      swagger.initOAuth({
        ...(response.status == 200 && {
          clientId: response.data["oauth2-client-id"],
        }),
        appName: "Workflow Launcher",
        scopes: "openid email profile",
      });
    });
  },
};
</script>

<style>
.swagger-ui .info .title {
  font-size: 36px !important;
}

.swagger-ui .info {
  margin: 0;
  background: none !important;
  border: none;
}

.swagger-ui .code,
.swagger-ui code {
  color: unset;
  background: none;
  padding: 0;
}

.swagger-ui .dialog-ux .modal-ux {
  border: none;
}

.swagger-ui .copy-to-clipboard button {
  padding-right: 10px;
}
</style>
