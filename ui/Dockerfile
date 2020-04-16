# Build stage 
FROM node:12-alpine as build-stage
WORKDIR /wfl_ui
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Production stage
FROM broadinstitute/openidc-proxy:tcell as production-stage
LABEL maintainer="hornet@broadinstitute.org"
WORKDIR /app
COPY --from=build-stage /wfl_ui/dist ./dist
