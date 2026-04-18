# Changelog

## [0.5.0](https://github.com/gvart/parleyroom/compare/v0.4.0...v0.5.0) (2026-04-18)


### Features

* **telegram:** add Login Widget endpoint for web portal linking ([#4](https://github.com/gvart/parleyroom/issues/4)) ([0f9d4e3](https://github.com/gvart/parleyroom/commit/0f9d4e39cd70d08d333f5aff23c583870c07acb9))

## [0.4.0](https://github.com/gvart/parleyroom/compare/v0.3.0...v0.4.0) (2026-04-17)


### Features

* **telegram:** add Mini App authentication and account linking ([8a8bf43](https://github.com/gvart/parleyroom/commit/8a8bf43e23305ef1fabc9646c8da6306d5c94405))

## [0.3.0](https://github.com/gvart/parleyroom/compare/v0.2.0...v0.3.0) (2026-04-17)


### Features

* **admin:** add admin module with user CRUD, unlock, password, status, and stats ([d886718](https://github.com/gvart/parleyroom/commit/d8867181746134112728bc7dc6f89cbf48f9f065))

## [0.2.0](https://github.com/gvart/parleyroom/compare/v0.1.0...v0.2.0) (2026-04-17)


### Features

* add CORS allowlist and restrict swagger to LAN hosts ([7926220](https://github.com/gvart/parleyroom/commit/792622034485e61da0a923dd4c7da6311575a244))
* **auth:** add refresh token flow with rotation ([2dbe358](https://github.com/gvart/parleyroom/commit/2dbe358aa3192aca06b0bfcb3201023cf2b4bfd4))
* **ci:** publish multi-arch (amd64 + arm64) manifest ([c63125c](https://github.com/gvart/parleyroom/commit/c63125c4aee47ab06fa65586beec4301387e1f98))
* **health:** add /healthz and /readyz endpoints for k8s probes ([c03e6f8](https://github.com/gvart/parleyroom/commit/c03e6f84ac5d6352187338e4e1f5aaf799f91660))
* implement remaining code review items ([063ecd2](https://github.com/gvart/parleyroom/commit/063ecd226cd6e82969294475fb44aaf72e89201a))
* initial parley-room backend ([18be27b](https://github.com/gvart/parleyroom/commit/18be27bb74a3812ba44ae866285eaaf612d128dd))


### Bug Fixes

* **avatar:** include upload timestamp in S3 key for unique cache-buster ([31b53a3](https://github.com/gvart/parleyroom/commit/31b53a3564c1d167bd0b1410118ee407a61debfb))
* **ci:** check out into parley-room/ so amper module name matches ([d2f965c](https://github.com/gvart/parleyroom/commit/d2f965c232eb72e9f9f9e560a1faa5aceb5c40a1))
* **ci:** use 'amper package -f executable-jar' to build jar ([70d4aa5](https://github.com/gvart/parleyroom/commit/70d4aa5b9b50b74969d54440e5ec0b71add68555))
* **swagger:** use Ktor host() route selector instead of pipeline intercept ([503b4e4](https://github.com/gvart/parleyroom/commit/503b4e48a09ee5245e40991ee6a4267789a7c009))
* **upload:** buffer multipart file parts with a size cap instead of requiring per-part Content-Length ([d4ebf02](https://github.com/gvart/parleyroom/commit/d4ebf02d2cd2188ddbf959cf386c3021c58b21f3))
