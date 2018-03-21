![](src/main/public/images/logo_white_framed_small.png "Slicebox") Slicebox
=====================================================

Service | Status | Description
------- | ------ | -----------
Bintray           | [![Download](https://api.bintray.com/packages/slicebox/slicebox/installers/images/download.svg) ](https://bintray.com/slicebox/slicebox/installers/_latestVersion) | Latest Version on Bintray
Travis            | [![Build Status](https://travis-ci.org/slicebox/slicebox.svg?branch=develop)](https://travis-ci.org/slicebox/slicebox.svg?branch=develop) | [Tests](https://travis-ci.org/slicebox/slicebox/)
Coveralls         | [![Coverage Status](https://coveralls.io/repos/github/slicebox/slicebox/badge.svg?branch=develop)](https://coveralls.io/github/slicebox/slicebox?branch=develop) | Code coverage
Gitter            | [![Join the chat at https://gitter.im/slicebox/slicebox](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/slicebox/slicebox?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) | Chatroom
Documentation     | - | [Wiki](https://github.com/slicebox/slicebox/wiki)
API Documentation | - | [REST API](http://slicebox.github.io/slicebox-api)

Slicebox is a microservice for safe sharing and easy access to medical images. The goal of the project is to facilitate research and collaboration between hospitals and universities. The service makes it easy to send images from the hosptial's PACS (image archive) to collaborators on the outside. Anonymization is handled automatically, releaving the hospital staff of the burden of making sure patient information does not leave the hospital network.

Features
--------

Slicebox provides a rich set of features for importing, exporting, managing and sharing image data. All features listed below are available as REST calls. The provided web interface covers most features.

* Browsing of images, either according to a Patient-Study-Series hierarchy, or using a flat view of all available series
* Querying, filtering and sorting of image (meta) data
* Upload and download of image data (DICOM datasets)
* Simple viewing of images
* Listing of DICOM attributes
* Tagging series with arbitrary tags and filtering search results on one or more tags
* Managing connections to other slicebox instances
* Sending images to and receiving images from other slicebox instances, with automatic anonymization of datasets
* Listing and searching anonymization information to map anonymized patient information to real patient data
* Setup of DICOM Service Class Providers (SCP). An SCP is a server accepting transfers of DICOM images. This functionality makes it possible to send images from a PACS system (image archive) to Slicebox
* Setup of DICOM Service Class Users (SCU). This is the opposite of an SCP. An SCU client makes it possible to export DICOM encapsulated images and results to PACS.
* Setup directory watches. DICOM files dropped into a directory watched by slicebox will be imported.
* Adding and removing users with priviliges
* Mapping sets of key-value pairs of DICOM attributes to user defined *series types* for easy integration with applications
* Managing rules for automatic forwarding of images from slicebox sources (a directory, a PACS system, a box connection etc) to destinations (a PACS system, a box connection etc).

User Manual
-----------

The user manual with information on installing, configuring and using slicebox is available as a project wiki at [github.com/slicebox/slicebox/wiki](https://github.com/slicebox/slicebox/wiki).

API
---

A full specification of the Slicebox API is available at [slicebox.github.io/slicebox-api](http://slicebox.github.io/slicebox-api).

Versioning
----------

This project uses [semantic versioning](http://semver.org). Versions are organized as MAJOR.MINOR.PATCH. Between minor versions, we only add functionality in a backwards compatible manner. This means that you can safely upgrade from e.g. version 1.2 to version 1.3. Patch versions are mainly used for bug fixes. Between major version, e.g. going from 1.6 to 2.0, we intrduce one or several breaking changes. Upgrading in this case will be more complex and it may be necessary to dump existing files and databases. Versions prior to 1.0 are pre-production releases and are not required to be backwards compatible. 

Development
-----------

Slicebox uses the YourKit profiler for optimizing code and finding performance bottlenecks. YourKit supports open source 
projects with its full-featured Java Profiler. YourKit, LLC is the creator of 
<a href="https://www.yourkit.com/java/profiler/">YourKit Java Profiler</a> and 
<a href="https://www.yourkit.com/.net/profiler/">YourKit .NET Profiler</a>, innovative and intelligent tools for 
profiling Java and .NET applications.

![YourKit logo](https://www.yourkit.com/images/yklogo.png)

License
-------

Slicebox is released under the [Apache License, version 2.0](./LICENSE).
