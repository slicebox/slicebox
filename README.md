![](./docs/logo_white_framed.png "Slicebox") Slicebox
=====================================================

Service | Status | Description
------- | ------ | -----------
Travis        | [![Build Status](https://travis-ci.org/KarlSjostrand/slicebox.svg?branch=develop)](https://travis-ci.org/KarlSjostrand/slicebox.svg?branch=develop) | Tests
Bintray       | [ ![Download](https://api.bintray.com/packages/karlsjostrand/slicebox/universal/images/download.svg) ](https://bintray.com/karlsjostrand/slicebox/universal/_latestVersion) | Latest Version on Bintray
Gitter        | [![Join the chat at https://gitter.im/KarlSjostrand/slicebox](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/KarlSjostrand/slicebox?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) | Chatroom
Documentation | - | [REST API](http://karlsjostrand.github.io/slicebox)

Slicebox is a microservice for safe sharing and easy access to medical images. The goal of the project is to facilitate research and collaboration between hospitals and universities. The service makes it easy to send images from the hosptial's PACS (image archive) to collaborators on the outside. Anonymization is handled automatically, releaving the hospital staff of the burden of making sure patient information does not leave the hospital network.

Features
--------

Slicebox provides a rich set of features for importing, exporting, managing and sharing image data. All features listed below are available as REST calls. The provided web interface covers most features.

* Browsing of images, either according to a Patient-Study-Series hierarchy, or using a flat view of all available series. 
* Querying, filtering and sorting of image (meta) data
* Upload and download of image data (DICOM datasets)
* Viewing of images
* Listing of DICOM attributes
* Managing connections to other slicebox instances
* Sending images to and receiving images from other slicebox instances
* Setup of DICOM Service Class Providers (SCP). An SCP is a server accepting transfers of DICOM images. This functionality makes it possible to send images from a PACS (image archive) to Slicebox.
* Setup of DICOM Service Class Users (SCU). This is the opposite of an SCP. An SCU client makes it possible to export DICOM encapsulated images and results to PACS.
* Setup directory watches. DICOM files dropped into a directory watched by slicebox will import the image.
* Protection of resources and routes using authentication and authorization. Users with certain priviliges can be added and removed.

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

We provide two types of installers, either a zip-file suitable for installation on any system, or `deb/rpm` packages for Debian/Red Hat Linux. The linux installers are easiest to use, slicebox will be setup and ready to use upon installation. The zip-file variant requires a little more configuration as described below.

### Universal zip package

* Download a zipped distribution of slicebox from [Bintray](https://bintray.com/karlsjostrand/slicebox/universal/_latestVersion).
* Unzip onto a suitable server computer. Any computer which is always on and which has a fixed (local or public) IP address will do.
* Configure the service by editing [conf/slicebox.conf](./src/main/resources/application.conf). In particular, the administrator (superuser) username and password can be configured as well as the hostname and port of the service.
* The `bin` folder contains start scripts for Windows and Linux/Unix/Mac OS.

### Windows - running Slicebox as a scheduled task

Slicebox does not (yet) provide an installer for Windows which configures slicebox to run as a Windows service. A similar behavior can be achieved using "Scheduled Tasks" in Windows.

* Open the Windows Task Scheduler
* From the menu, select Action -> Create Task...
* In the 'General' tab, name the task 'slicebox' and select 'Run whether user is logged on or not'
* In the 'Triggers' tab, create a new trigger which begins 'At startup'
* In the 'Actions' tab, create a new action of the 'Start a program' type pointing to the `bin/slicebox.bat` startup script in the slicebox installation directory
* Review other task settings and see if any apply to your installation

There should now be a slicebox task in the list of active tasks. Restart the computer, or start it directly from the list of scheduled tasks

### Linux - installation and configuration

We provide `.deb` and `.rpm` packages, both available on [Bintray](https://bintray.com/karlsjostrand/slicebox/universal/_latestVersion). These installers set up slicebox to run as a background process, or service. The following file structure is used:

Folder                       | Description
------                       | -----------
`/usr/share/slicebox`        | Installation directory. The database, logs, configureations and stored DICOM files reside here.
`/etc/default/slicebox.conf` | Config file for environment variables and other settings applied before the application starts
`/etc/slicebox`              | Sym-link to slicebox configuration folder
`/var/log/slicebox`          | Location of slicebox log files

Once installed, edit the slicebox configuration files as needed (administrator credentials and hostname/port).

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
