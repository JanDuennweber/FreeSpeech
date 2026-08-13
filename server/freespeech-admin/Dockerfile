# ── Build stage ───────────────────────────────────────────────────────────────
FROM ruby:3.3-slim AS build

RUN apt-get update -qq && apt-get install -y --no-install-recommends \
      build-essential \
      libsqlite3-dev \
      git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install gems (production only — excludes development/test)
COPY Gemfile Gemfile.lock ./
RUN bundle config set --local without "development test" \
 && bundle install --jobs 4 --retry 3

# Copy application source
COPY . .

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM ruby:3.3-slim

RUN apt-get update -qq && apt-get install -y --no-install-recommends \
      libsqlite3-0 \
    && rm -rf /var/lib/apt/lists/*

# Non-root user
RUN useradd -m -u 1001 -s /bin/bash freespeech

WORKDIR /app

# Copy gems and app from build stage
COPY --from=build /usr/local/bundle /usr/local/bundle
COPY --from=build --chown=freespeech:freespeech /app /app

# Persistent data directories — mount volumes here
RUN mkdir -p /data/logs /data/audio /app/storage \
 && chown -R freespeech:freespeech /data /app/storage

# Make entrypoint executable
RUN chmod +x bin/docker-entrypoint

USER freespeech

EXPOSE 3001

ENTRYPOINT ["bin/docker-entrypoint"]
CMD ["bundle", "exec", "puma", "-C", "config/puma.rb"]
