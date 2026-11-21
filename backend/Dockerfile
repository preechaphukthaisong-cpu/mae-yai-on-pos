FROM node:22-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY migrations ./migrations
COPY src ./src
USER node
EXPOSE 8080
CMD ["sh", "-c", "node src/migrate.js && node src/server.js"]
