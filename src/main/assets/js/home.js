(function () {
   'use strict';
}());

angular.module('slicebox.home', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/', {
    templateUrl: '/assets/partials/home.html',
    controller: 'HomeCtrl'
  });
})

.controller('HomeCtrl', function($scope, $http, $modal, $q, openConfirmationDeleteModal) {
    // Initialization
    $scope.patientActions =
        [
            {
                name: 'Send',
                action: confirmSendPatients
            },
            {
                name: 'Delete',
                action: confirmDeletePatients
            }
        ];

    $scope.studyActions =
        [
            {
                name: 'Send',
                action: confirmSendStudies
            },
            {
                name: 'Delete',
                action: confirmDeleteStudies
            }
        ];

    $scope.seriesActions =
        [
            {
                name: 'Send',
                action: confirmSendSeries
            },
            {
                name: 'Delete',
                action: confirmDeleteSeries
            }
        ];

    $scope.fileImageActions =
        [
            {
                name: 'Send',
                action: confirmSendImageFiles
            }
        ];

    $scope.callbacks = {};

    $scope.uiState = {
        errorMessage: null,
        selectedPatient: null,
        selectedStudy: null,
        selectedSeries: null,
        seriesDetails: {
            imageUrls: [],
            imageHeight: 50,
            isWindowManual: false,
            windowMin: undefined,
            windowMax: undefined
        }
    };

    // Scope functions
    $scope.loadPatients = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadPatientsUrl = '/api/metadata/patients?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            var orderByPropertyName = orderByProperty.substring(0, orderByProperty.indexOf('['));
            loadPatientsUrl = loadPatientsUrl + '&orderby=' + orderByPropertyName;
            
            if (orderByDirection === 'ASCENDING') {
                loadPatientsUrl = loadPatientsUrl + '&orderascending=true';
            } else {
                loadPatientsUrl = loadPatientsUrl + '&orderascending=false';
            }
        }

        if (filter) {
            loadPatientsUrl = loadPatientsUrl + '&filter=' + encodeURIComponent(filter);
        }

        var loadPatientsPromise = $http.get(loadPatientsUrl);

        loadPatientsPromise.error(function(error) {
            appendErrorMessage('Failed to load patients: ' + error);
        });

        return loadPatientsPromise;
    };

    $scope.patientSelected = function(patient) {
        $scope.uiState.selectedPatient = patient;
        $scope.callbacks.studiesTable.reset();
    };

    $scope.loadStudies = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedPatient === null) {
            return [];
        }

        var loadStudiesPromise = $http.get('/api/metadata/studies?startindex=' + startIndex + '&count=' + count + '&patientId=' + $scope.uiState.selectedPatient.id);

        loadStudiesPromise.error(function(error) {
            appendErrorMessage('Failed to load studies: ' + error);
        });

        return loadStudiesPromise;
    };

    $scope.studySelected = function(study) {
        $scope.uiState.selectedStudy = study;
        $scope.callbacks.seriesTable.reset();
    };

    $scope.loadSeries = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedStudy === null) {
            return [];
        }

        var loadSeriesPromise = $http.get('/api/metadata/series?startindex=' + startIndex + '&count=' + count + '&studyId=' + $scope.uiState.selectedStudy.id);

        loadSeriesPromise.error(function(error) {
            appendErrorMessage('Failed to load series: ' + error);
        });

        return loadSeriesPromise;
    };

    $scope.seriesSelected = function(series) {
        $scope.uiState.selectedSeries = series;
        $scope.callbacks.imageAttributesTable.reset();
        $scope.uiState.seriesDetails.imageUrls = [];
        $scope.uiState.seriesDetails.windowMin = undefined;
        $scope.uiState.seriesDetails.windowMax = undefined;
        $scope.updateImageUrls();
    };

    $scope.loadImageAttributes = function(startIndex, count) {
        if ($scope.uiState.selectedSeries === null) {
            return [];
        }

        var imagesPromise = $http.get('/api/metadata/images?seriesId=' + $scope.uiState.selectedSeries.id);

        imagesPromise.error(function(reason) {
            appendErrorMessage('Failed to load images for series: ' + error);            
        });

        var attributesPromise = imagesPromise.then(function(images) {
            if (images.data.length > 0) {
                return $http.get('/api/images/' + images.data[0].id + '/attributes');
            } else {
                return [];
            }
        }, function(error) {
            appendErrorMessage('Failed to load image attributes: ' + error);
        });

        return attributesPromise;
    };

    $scope.updateImageUrls = function() {
        $scope.uiState.seriesDetails.imageUrls = [];

        if ($scope.uiState.selectedSeries !== null) {

            $http.get('/api/metadata/images?seriesId=' + $scope.uiState.selectedSeries.id).success(function(images) {

                $scope.uiState.seriesDetails.imageUrls = [];

                angular.forEach(images, function(image) {

                    $http.get('/api/images/' + image.id + '/imageinformation').success(function(info) {
                        // window min and max are defined by the first image of the series
                        if (!$scope.uiState.seriesDetails.windowMin) {
                            $scope.uiState.seriesDetails.windowMin = info.minimumPixelValue;
                        }
                        if (!$scope.uiState.seriesDetails.windowMax) {
                            $scope.uiState.seriesDetails.windowMax = info.maximumPixelValue;
                        }
                        for (var i = 0; i < info.numberOfFrames; i++) {
                            var url = '/api/images/' + image.id + '/png?framenumber=' + (i + 1);
                            if ($scope.uiState.seriesDetails.isWindowManual) {
                                url = url + 
                                    '&windowmin=' + $scope.uiState.seriesDetails.windowMin + 
                                    '&windowmax=' + $scope.uiState.seriesDetails.windowMax;
                            }
                            if (!isNaN(parseInt($scope.uiState.seriesDetails.imageHeight))) {
                                url = url + 
                                    '&imageheight=' + $scope.uiState.seriesDetails.imageHeight;
                            }
                            $scope.uiState.seriesDetails.imageUrls.push(url);
                        }
                    }).error(function(error) {
                        appendErrorMessage('Failed to load image information: ' + error);            
                    });

                });
            }).error(function(reason) {
                appendErrorMessage('Failed to load images for series: ' + reason);                        
            });

        }
    };

    $scope.closeErrorMessageAlert = function() {
        $scope.uiState.errorMessage = null;
    };

    // Private functions
    function appendErrorMessage(errorMessage) {
        if ($scope.uiState.errorMessage === null) {
            $scope.uiState.errorMessage = errorMessage;
        } else {
            $scope.uiState.errorMessage = $scope.uiState.errorMessage + '\n' + errorMessage;
        }
    }

    function confirmDeletePatients(patients) {
        var deleteConfirmationText = 'Permanently delete ' + patients.length + ' patients?';

        return openConfirmationDeleteModal('Delete Patients', deleteConfirmationText, function() {
            return deletePatients(patients);
        });
    }

    function deletePatients(patients) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(patients, function(patient) {
            deletePromise = $http.delete('/api/metadata/patients/' + patient.id);
            deletePromises.push(deletePromise);

            deletePromise.error(function(error) {
                appendErrorMessage('Failed to delete patient: ' + error);
            });
        });

        return $q.all(deletePromises);
    }

    function confirmDeleteStudies(studies) {
        var deleteConfirmationText = 'Permanently delete ' + studies.length + ' studies?';

        return openConfirmationDeleteModal('Delete Studies', deleteConfirmationText, function() {
            return deleteStudies(studies);
        });
    }

    function deleteStudies(studies) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(studies, function(study) {
            deletePromise = $http.delete('/api/metadata/studies/' + study.id);
            deletePromises.push(deletePromise);

            deletePromise.error(function(error) {
                appendErrorMessage('Failed to delete study: ' + error);
            });
        });

        return $q.all(deletePromises);
    }

    function confirmDeleteSeries(series) {
        var deleteConfirmationText = 'Permanently delete ' + series.length + ' series?';

        return openConfirmationDeleteModal('Delete Series', deleteConfirmationText, function() {
            return deleteSeries(series);
        });
    }

    function deleteSeries(series) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(series, function(theSeries) {
            deletePromise = $http.delete('/api/metadata/series/' + theSeries.id);
            deletePromises.push(deletePromise);

            deletePromise.error(function(error) {
                appendErrorMessage('Failed to delete series: ' + error);
            });
        });

        return $q.all(deletePromises);
    }

    function confirmSendImageFiles(imageFiles) {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                resolve: {
                    title: function() {
                        return 'Send ' + imageFiles.length + ' Image Files';
                    },
                    sendCallback: function() {
                        return function(remoteBoxId) {
                            return sendImageFiles(remoteBoxId, imageFiles);
                        };
                    }
                }
            });
    }

    function sendImageFiles(remoteBoxId, imageFiles) {
        var imageIds = [];

        angular.forEach(imageFiles, function(imageFile) {
            imageIds.push(imageFile.id);
        });

        return $http.post('/api/boxes/' + remoteBoxId + '/sendimages', imageIds);
    }

    function confirmSendSeries(series) {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                resolve: {
                    title: function() {
                        return 'Send ' + series.length + ' Series';
                    },
                    sendCallback: function() {
                        return function(remoteBoxId) {
                            return sendSeries(remoteBoxId, series);
                        };
                    }
                }
            });
    }

    function sendSeries(remoteBoxId, series) {
        var seriesIds = [];

        angular.forEach(series, function(theSeries) {
            seriesIds.push(theSeries.id);
        });

        return $http.post('/api/boxes/' + remoteBoxId + '/sendseries', seriesIds);
    }

    function confirmSendStudies(studies) {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                resolve: {
                    title: function() {
                        return 'Send ' + studies.length + ' Studies';
                    },
                    sendCallback: function() {
                        return function(remoteBoxId) {
                            return sendStudies(remoteBoxId, studies);
                        };
                    }
                }
            });
    }
    
    function sendStudies(remoteBoxId, studies) {
        var studyIds = [];

        angular.forEach(studies, function(study) {
            studyIds.push(study.id);
        });

        return $http.post('/api/boxes/' + remoteBoxId + '/sendstudies', studyIds);
    }

    function confirmSendPatients(patients) {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                resolve: {
                    title: function() {
                        return 'Send ' + patients.length + ' Patients';
                    },
                    sendCallback: function() {
                        return function(remoteBoxId) {
                            return sendPatients(remoteBoxId, patients);
                        };
                    }
                }
            });
    }
    
    function sendPatients(remoteBoxId, patients) {
        var patientIds = [];

        angular.forEach(patients, function(patient) {
            patientIds.push(patient.id);
        });

        return $http.post('/api/boxes/' + remoteBoxId + '/sendpatients', patientIds);
    }

})

.controller('SendImageFilesModalCtrl', function($scope, $modalInstance, $http, $q, title, sendCallback) {
    // Initialization
    $scope.title = title;

    $scope.uiState = {
        errorMessages: [],
        selectedReceiver: null
    };

    // Scope functions
    $scope.loadBoxesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes');
    };

    $scope.boxSelected = function(box) {
        $scope.uiState.selectedReceiver = box;
    };

    $scope.closeErrorMessageAlert = function(errorIndex) {
        $scope.uiState.errorMessages.splice(errorIndex, 1);
    };

    $scope.sendButtonClicked = function() {
        var sendPromise = sendCallback($scope.uiState.selectedReceiver.id);

        $scope.uiState.sendInProgress = true;

        sendPromise.error(function(data) {
            $scope.uiState.errorMessages.push(data);
        });

        sendPromise.then(function() {
            $modalInstance.close();
        });

        sendPromise.finally(function() {
            $scope.uiState.sendInProgress = false;
        });
    };

    $scope.cancelButtonClicked = function() {
        $modalInstance.dismiss();
    };
});