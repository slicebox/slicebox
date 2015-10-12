![](./docs/logo_white_framed.png "Slicebox") Slicebox
=====================================================

Service | Status | Description
------- | ------ | -----------
Bintray       | [ ![Download](https://api.bintray.com/packages/slicebox/slicebox/installers/images/download.svg) ](https://bintray.com/slicebox/slicebox/installers/_latestVersion) | Latest Version on Bintray
Travis        | [![Build Status](https://travis-ci.org/slicebox/slicebox.svg?branch=develop)](https://travis-ci.org/slicebox/slicebox.svg?branch=develop) | [Tests](https://travis-ci.org/slicebox/slicebox/)
Coveralls     | [![Coverage Status](https://coveralls.io/repos/slicebox/slicebox/badge.svg?branch=develop&service=github)](https://coveralls.io/github/slicebox/slicebox?branch=develop) | Code coverage
Gitter        | [![Join the chat at https://gitter.im/slicebox/slicebox](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/slicebox/slicebox?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge) | Chatroom
Documentation | - | [REST API](http://slicebox.github.io/slicebox)

Slicebox is a microservice for safe sharing and easy access to medical images. The goal of the project is to facilitate research and collaboration between hospitals and universities. The service makes it easy to send images from the hosptial's PACS (image archive) to collaborators on the outside. Anonymization is handled automatically, releaving the hospital staff of the burden of making sure patient information does not leave the hospital network.

Features
--------

Slicebox provides a rich set of features for importing, exporting, managing and sharing image data. All features listed below are available as REST calls. The provided web interface covers most features.

* Browsing of images, either according to a Patient-Study-Series hierarchy, or using a flat view of all available series. 
* Querying, filtering and sorting of image (meta) data
* Upload and download of image data (DICOM datasets)
* Viewing of images
* Listing of DICOM attributes
* Tagging series with arbitrary tags and filtering search results on one or more tags
* Mapping sets of key-value pairs of DICOM attributes to user defined *series types* for easy integration with applications
* Managing connections to other slicebox instances
* Sending images to and receiving images from other slicebox instances, with automatic anonymization of datasets
* Listing and searching anonymization information to map anonymized patient information to real patient data
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

### Java

Slicebox runs on the Java Virtual Machine and requires a Java 8 runtime environment. If you machine does not have Java 8 installed, download and install from [java.com](https://java.com) (a JRE i sufficient). Make sure you have the `java` command located in the `bin` folder of your Java installation on the path. Check this by typing `java -version` in a command window. The response should be that you are running Java version 8.

We provide two types of installers, either a zip-file suitable for installation on any system, or deb/rpm packages for Debian/Red Hat Linux. When using the zip-file, it is up to the user installing slicebox to install the program as a service. The linux installers are configured to automatically install slicebox as a service which is always running.

### Universal zip package

* Download a zipped distribution of slicebox from [Bintray](https://bintray.com/slicebox/slicebox/installers/_latestVersion).
* Unzip onto a suitable server computer. Any computer which is always on and which has a fixed (local or public) IP address will do.
* Configure the service by editing [conf/slicebox.conf](./src/main/resources/slicebox.conf). In particular, the administrator (superuser) username and password can be configured along with the hostname and port of the service, and paths to the database and file storage. 
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

### Linux (Debian) - installation and configuration

We provide `.deb` and `.rpm` packages, both available on [Bintray](https://bintray.com/slicebox/slicebox/installers/_latestVersion). These installers set up slicebox to run as a background process, or service. Install `.deb` packages using the command
```
sudo dpkg -i <package name>.deb
```
Uninstall slicebox and remove all files including configuration with the command
```
sudo apt-get purge slicebox
```

The following file structure is used:

Folder                       | User     | Description
------                       | ----     | -----------
`/usr/share/slicebox`        | root     | Installation directory
`/etc/default/slicebox.conf` | root     | Config file for environment variables and other settings applied before the application starts
`/etc/slicebox`              | root     | Sym-link to slicebox configuration folder
`/var/log/slicebox`          | slicebox | Location of slicebox log files
`/var/run/slicebox`          | slicebox | Currently not used

The service can be controlled using commands such as `sudo restart slicebox`, `sudo stop slicebox` and `sudo status slicebox`.

During the installation a user and group named slicebox is created, as indicated in the table above. The service is run using this user, which means that files created by slicebox (such as the database files and stored DICOM files) must reside in a directory in which the slicebox user has the necessary permissions. Upon installation, the default settings in `/etc/slicebox/slicebox.conf` point to the installation directory itself, which is owned by root. This means that slicebox will not run correclty (there will be a log error message indicating this). We suggest the following changes:

* Create a directory `/var/slicebox` owned by the slicebox user and group. 
* Change the following settings in the config file `/etc/slicebox/slicebox.conf`:
   * `slicebox.dicom-files.path = "/var/slicebox/dicom-files"`
   * `slicebox.database.path = "/var/slicebox/slicebox"`
* Make other applicable changes to the configuration. You probably wish to override `http.host`, `http.port`, `slicebox.superuser.user` and `slicebox.superuser.password`.
* Restart slicebox using `sudo restart slicebox`
* The service should now be available at your specified host name and port number. Check the log to make sure the service is running as intended.


### Installation notes

* Hospitals often restrict internet access to a limited set of ports, usually ports 80 and 443 (for SSL). Slicebox instances that should communicate with hospital instances may be required to run on port 80 for this reason. This is difficult to accompish on Linux servers as users other than root are not permitted to open ports below 1024. One possibility is to run slicebox on a high port number such as 5000, and set up a reverse proxy using e.g. Apache or Nginx which redirects incoming traffic to your site example.com:80 to slicebox running on localhost:5000. When running slicebox behind a reverse proxy the service is running on a local hostname (typically localhost:5000) while it is accessed via a public hostname. To separate these, the `host` and `port` configurations under the `http` group specifies the local hostname, while adding `host` and `port` under the `slicebox` group specifies the public hostname. The resulting example configuration is

```
http {
	host = "localhost" // local hostname
	port = 5000        // local port
}

slicebox {

	host = "slicebox.se" // public hostname
	port = 80            // public port

	dicom-files {
		path = "/var/slicebox"
	}

	database {
		path = "/var/slicebox/slicebox"
	}
	  
	superuser {
		user = "admin"
		password = "secret"
	}

}
```


Integration with Applications
-----------------------------

Developing medical imaging applications for clinical use is an undertaking. Ideally, developers should be allowed to focus on the application code itself, and worry less about imaging hardware, protocols, communication and patient integrity. In reality, such obstacles can consume a majority of project resources. By letting a Slicebox instance take care of the communication with image sources as well as storage and management of image data, the path to a running imaging application is shortened considerably. 

Most imaging applications are created with a certain type of image in mind. For instance, you may be developing a program that calculates the dynamic uptake of a radioactive isotope in the kidneys (a *renography*). When your program starts you want it to contact the connected Slicebox instance and ask for all images of this type. But how can you distinguish such images from others? The vital information resides in the attributes of the DICOM files. Let's assume that by inspecting the attributes of the images you have received from the hospital you work at or collaborate with, you notice that these images share the following unique characteristic.

* `SeriesDescription` = `RENOGRAPHY`

To make sure that you get the right type of renography image, you may decide to be a little more specific:

* `SeriesDescription` = `RENOGRAPHY` 
* `Modality` = `NM`
* `NumberOfFrames` = `80`

Now, the `NuberOfFrames` tag is not indexed by Slicebox so you cannot write this rule as a query to Slicebox. Instead, Slicebox introduces the concept of *series types* which are a set of labels associated with each series of the DICOM hierarchy. Administrator users of Slicebox can define any number of series types by just coming up with a name for each of them. In this case we create the series type `RENO` through the series type view in the Slicebox GUI. A series type can be associated with one or more rules of the type defined above. We can for instance associate `RENO` with the rule of three attributes above. Series containing DICOM attributes that exactly match all three attribute equalities above are assigned to the `RENO` series type. If a second rule is defined for `RENO`, say

* `SeriesDescription` = `RENOGRAPHY` 
* `Modality` = `NM`
* `NumberOfFrames` = `40`

then series mathing one or both rules will be labelled `RENO`. Series can have zero, one or many series types assigned to them. 

The maker of the renography application can now ask Slicebox to list patients, studies and series associated with the `RENO` series type. When the application is moved to another hospital or starts receiving images from another camera, this may mean that image attributes look different. It is then simple to define new rules for the `RENO` series type that make sure these new images are also labelled correctly.


Integration with Matlab
-----------------------

Slicebox integrates smoothly with Matlab such that a research team can store and manage their entire collection of DICOM images on a central Slicebox instance and access these images using simple Matab commands. This has major benefits. All team members have access to the same images, code becomes easier to move between computers and image data will be properly ordered and can be searched, queried and labelled. To reduce network traffic, increase speed and make it possible to work also in situations when the Slicebox instance cannot be reached, images are cached locally. 

The Matlab integration library for Slicebox resides in the branch [matlab-integration](https://github.com/slicebox/slicebox/tree/matlab-integration). The [README](https://github.com/slicebox/slicebox/blob/matlab-integration/README.md) file of that branch provides more information.

API
---

A full specification of the Slicebox API is available at [slicebox.github.io/slicebox](http://slicebox.github.io/slicebox), displayed using [Swagger UI](http://swagger.io).

Versioning
----------

This project uses [semantic versioning](http://semver.org). Versions are organized as MAJOR.MINOR.PATCH. Between minor versions, we only add functionality in a backwards compatible manner. This means that you can safely upgrade from e.g. version 1.2 to version 1.3. Patch versions are mainly used for bug fixes. Between major version, e.g. going from 1.6 to 2.0, we intrduce one or several breaking changes. Upgrading in this case will be more complex and it may be necessary to dump existing files and databases. Versions prior to 1.0 are pre-production releases and are not required to be backwards compatible. 

License
-------

Slicebox is released under the [Apache License, version 2.0](./LICENSE).
