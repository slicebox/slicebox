Slicebox
=======

[![Build Status](https://travis-ci.org/KarlSjostrand/slicebox.svg?branch=develop)](https://travis-ci.org/KarlSjostrand/slicebox.svg?branch=develop) [![Join the chat at https://gitter.im/KarlSjostrand/slicebox](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/KarlSjostrand/slicebox?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Slicebox is a microservice for safe sharing and easy access to medical images. The goal of the project is to facilitate research and collaboration between hospitals and universities. The service makes it easy to send images from the hosptial's PACS (image archive) to collaborators on the outside. Anonymization is handled automatically, releaving the hospital staff of the burden of making sure patient information does not leave the hospital network.

Example Usage
-------------

Our canonical use case is a hosptial research group collaborating with researchers in medical image analysis at a university. Slicebox instances are installed at both sites and are setup to communicate with each other. Furthermore, the hospital instance is setup to receive images from the hospital PACS system. Images can now be exported from PACS to the local slicebox instance easily. Exported images can be browsed and handled using slicebox's intuitive web interface. From here, images can be sent to the slicebox instance installed at the university. Slicebox will handle anonymization according to the DICOM standard before images leave the hosptial. On the university side, researchers can now access these images both using the web interface as well as an extensive REST api. We provide tight integration with Matlab, but using slicebox from other environments for technical computing is straight-forward. Benefits of storing image data in slicebox as opposed to local files include

* All research group members have access to all data
* Code can be shared more easily since data paths will work across computers
* Organizing DICOM files is cumbersome. An API which implements the DICOM hierarchy of patient-study-series-image reduces this burden and reduces the risk of using the wrong data

Installation
------------

* Download a zipped distribution of slicebox from the [dist/](./dist) folder.
* Unzip onto a suitable server computer. Any computer which is always on and which has a fixed (local or public) IP address will do.
* Configure the service by editing the [conf/slicebox.conf](./src/main/resources/slicebox.conf) and [conf/http.conf](./src/main/resources/http.conf). The former contains the administrator username and password while the latter specifies hostname and port of the service.
* The `bin` folder contains start scripts for Windows and Linux/Unix/Mac OS. Make sure the service is started on server startup. 

API
---

License
-------

Slicebox is release under the [Apache License, version 2.0](./LICENSE).
