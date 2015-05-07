![](./docs/logo_white_framed.png "Slicebox") Slicebox
=====================================================

Service | Status | Description
------- | ------ | -----------
Travis  | [![Build Status](https://travis-ci.org/KarlSjostrand/slicebox.svg?branch=develop)](https://travis-ci.org/KarlSjostrand/slicebox.svg?branch=develop) | Tests
Bintray | [ ![Download](https://api.bintray.com/packages/karlsjostrand/slicebox/universal/images/download.svg) ](https://bintray.com/karlsjostrand/slicebox/universal/_latestVersion) | Latest Version on Bintray
Gitter | [![Join the chat at https://gitter.im/KarlSjostrand/slicebox](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/KarlSjostrand/slicebox?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) | Chatroom


Slicebox is a microservice for safe sharing and easy access to medical images. The goal of the project is to facilitate research and collaboration between hospitals and universities. The service makes it easy to send images from the hosptial's PACS (image archive) to collaborators on the outside. Anonymization is handled automatically, releaving the hospital staff of the burden of making sure patient information does not leave the hospital network.

Example Usage
-------------

Our canonical use case is a hospital research group collaborating with researchers in medical image analysis at a university. Slicebox instances are installed at both sites and are setup to communicate with each other. Furthermore, the hospital instance is setup to receive images from the hospital PACS system as shown in the image below.

![example slicebox setup](./docs/hospital-uni.png "Example use case setup")

Images can now be exported from PACS to the local slicebox instance easily. Exported images can be browsed and handled using slicebox's intuitive web interface. From here, images can be sent to the slicebox instance installed at the university. Slicebox will handle anonymization according to the DICOM standard before images leave the hosptial. On the university side, researchers can now access these images both using the web interface as well as an extensive REST api. We provide tight integration with Matlab, but using slicebox from other environments for technical computing is straight-forward. Benefits of storing image data in slicebox as opposed to local files include

* All research group members have access to all data
* Code can be shared more easily since data paths will work across computers
* Organizing DICOM files is cumbersome. An API which implements the DICOM hierarchy of patient-study-series-image reduces this burden and reduces the risk of analyzing the wrong data

Installation
------------

* Download a zipped distribution of slicebox from [Bintray](https://bintray.com/karlsjostrand/slicebox/universal/_latestVersion).
* Unzip onto a suitable server computer. Any computer which is always on and which has a fixed (local or public) IP address will do.
* Configure the service by editing [conf/slicebox.conf](./src/main/resources/application.conf). In particular, the administrator (superuser) username and password can be configured as well as the hostname and port of the service.
* The `bin` folder contains start scripts for Windows and Linux/Unix/Mac OS. Make sure the service is started on server startup. 

Integration with Applications
-----------------------------

Integration with Matlab
-----------------------

API
---

A full specification of the Slicebox API is available at [karlsjostrand.github.io/slicebox](http://karlsjostrand.github.io/slicebox), displayed using [Swagger UI](http://swagger.io).

Versioning
----------

This project uses [semantic versioning](http://semver.org). Versions are organized as MAJOR.MINOR.PATCH. Between minor versions, we only add functionality in a backwards compatible manner. This means that you can safely upgrade from e.g. version 1.2 to version 1.3. Patch versions are mainly used for bug fixes. Between major version, e.g. going from 1.6 to 2.0, we intrduce one or several breaking changes. Upgrading in this case will be more complex and it may be necessary to dump existing files and databases. Versions prior to 1.0 are pre-production releases and are not required to be backwards compatible. 

License
-------

Slicebox is released under the [Apache License, version 2.0](./LICENSE).
