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

.controller('HomeCtrl', function($scope, $http, $mdDialog, $q, openConfirmationDeleteModal) {
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
                name: 'Send to SCP',
                requiredSelectionCount: 1,
                action: confirmSendSeriesToScp
            },   
            {
                name: 'Delete',
                action: confirmDeleteSeries
            }
        ];

    $scope.callbacks = {};

    $scope.uiState = {};
    $scope.uiState.selectedPatient = null;
    $scope.uiState.selectedStudy = null;
    $scope.uiState.selectedSeries = null;
    $scope.uiState.loadPngImagesInProgress = false;
    $scope.uiState.seriesDetails = {
        leftColumnSelectedTabIndex: 0,
        rightColumnSelectedTabIndex: 0,
        pngImageUrls: [],
        imageHeight: 0,
        images: 1,
        isWindowManual: false,
        windowMin: 0,
        windowMax: 100
    };


    // Scope functions

    $scope.loadPatients = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadPatientsUrl = '/api/metadata/patients?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            var orderByPropertyName = capitalizeFirst(orderByProperty.substring(0, orderByProperty.indexOf('[')));
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
            $scope.showErrorMessage('Failed to load patients: ' + error);
        });

        return loadPatientsPromise;
    };

    $scope.patientSelected = function(patient) {
        if (patient !== $scope.uiState.selectedPatient) {
            $scope.studySelected(null);
            $scope.uiState.selectedPatient = patient;
            if ($scope.callbacks.studiesTable) { 
                $scope.callbacks.studiesTable.reset();
            }
        }
    };

    $scope.loadStudies = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedPatient === null) {
            return [];
        }

        var loadStudiesPromise = $http.get('/api/metadata/studies?startindex=' + startIndex + '&count=' + count + '&patientid=' + $scope.uiState.selectedPatient.id);

        loadStudiesPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load studies: ' + error);
        });

        return loadStudiesPromise;
    };

    $scope.studySelected = function(study) {
        if (study !== $scope.uiState.selectedStudy) {
            $scope.seriesSelected(null);
            $scope.uiState.selectedStudy = study;
            if ($scope.callbacks.imageAttributesTable) { 
                $scope.callbacks.imageAttributesTable.reset();
            }
        }
    };

    $scope.loadSeries = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedStudy === null) {
            return [];
        }

        var loadSeriesPromise = $http.get('/api/metadata/series?startindex=' + startIndex + '&count=' + count + '&studyid=' + $scope.uiState.selectedStudy.id);

        loadSeriesPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load series: ' + error);
        });

        return loadSeriesPromise;
    };

    $scope.loadFlatSeries = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadFlatSeriesUrl = '/api/metadata/series?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            var orderByPropertyName = capitalizeFirst(orderByProperty.substring(orderByProperty.indexOf('.') + 1, orderByProperty.indexOf('[')));
            loadFlatSeriesUrl = loadFlatSeriesUrl + '&orderby=' + orderByPropertyName;
            
            if (orderByDirection === 'ASCENDING') {
                loadFlatSeriesUrl = loadFlatSeriesUrl + '&orderascending=true';
            } else {
                loadFlatSeriesUrl = loadFlatSeriesUrl + '&orderascending=false';
            }
        }

        if (filter) {
            loadFlatSeriesUrl = loadFlatSeriesUrl + '&filter=' + encodeURIComponent(filter);
        }

        var loadFlatSeriesPromise = $http.get(loadFlatSeriesUrl);

        loadFlatSeriesPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load series: ' + error);
        });

        return loadFlatSeriesPromise;
    };

    $scope.seriesSelected = function(series) {
        if (series !== $scope.uiState.selectedSeries) {
            $scope.uiState.selectedSeries = series;

            $scope.uiState.seriesDetails.pngImageUrls = [];

            if ($scope.callbacks.imageAttributesTable) { 
                $scope.callbacks.imageAttributesTable.reset(); 
            }
            if ($scope.callbacks.datasetsTable) { 
                $scope.callbacks.datasetsTable.reset();
            }

            $scope.updatePNGImageUrls();
        }
    };

    $scope.flatSeriesSelected = function(flatSeries) {

        if (flatSeries === null) {
            $scope.patientSelected(null);
        } else {
            $scope.patientSelected(flatSeries.patient);
            $scope.studySelected(flatSeries.study);
            $scope.seriesSelected(flatSeries.series);
        }
    };

    $scope.loadImageAttributes = function(startIndex, count) {
        if ($scope.uiState.selectedSeries === null) {
            return [];
        }

        var imagesPromise = $http.get('/api/metadata/images?seriesid=' + $scope.uiState.selectedSeries.id);

        imagesPromise.error(function(reason) {
            $scope.showErrorMessage('Failed to load images for series: ' + error);            
        });

        var attributesPromise = imagesPromise.then(function(images) {
            if (images.data.length > 0) {
                return $http.get('/api/images/' + images.data[0].id + '/attributes').error(function(error) {
                    $scope.showErrorMessage('Failed to load image attributes: ' + error);
                });
            } else {
                return [];
            }
        });

        return attributesPromise;
    };

    $scope.updatePNGImageUrls = function() {
        $scope.uiState.seriesDetails.pngImageUrls = [];

        if ($scope.uiState.selectedSeries !== null) {
            $scope.uiState.loadPngImagesInProgress = true;

            $http.get('/api/metadata/images?seriesid=' + $scope.uiState.selectedSeries.id).success(function(images) {

                var generateMore = true;

                angular.forEach(images, function(image, imageIndex) {

                    if (imageIndex < $scope.uiState.seriesDetails.images) {

                        $http.get('/api/images/' + image.id + '/imageinformation').success(function(info) {
                            if (!$scope.uiState.seriesDetails.isWindowManual) {
                                $scope.uiState.seriesDetails.windowMin = info.minimumPixelValue;
                                $scope.uiState.seriesDetails.windowMax = info.maximumPixelValue;
                            }
                            $http.post('/api/users/generateauthtokens?n=' + info.numberOfFrames).success(function(tokens) {
                                for (var j = 0; j < info.numberOfFrames && generateMore; j++) {

                                    var url = '/api/images/' + image.id + '/png'+ '?authtoken=' + tokens[j].token + '&framenumber=' + (j + 1);
                                    if ($scope.uiState.seriesDetails.isWindowManual) {
                                        url = url + 
                                            '&windowmin=' + $scope.uiState.seriesDetails.windowMin + 
                                            '&windowmax=' + $scope.uiState.seriesDetails.windowMax;
                                    }
                                    if (!isNaN(parseInt($scope.uiState.seriesDetails.imageHeight))) {
                                        url = url + 
                                            '&imageheight=' + $scope.uiState.seriesDetails.imageHeight;
                                    }
                                    var frameIndex = Math.max(0, info.frameIndex - 1)*Math.max(1, info.numberOfFrames) + (j + 1);
                                    $scope.uiState.seriesDetails.pngImageUrls.push({ url: url, frameIndex: frameIndex });
                                    generateMore = $scope.uiState.seriesDetails.pngImageUrls.length < $scope.uiState.seriesDetails.images && 
                                                    !(imageIndex === images.length - 1 && j == info.numberOfFrames - 1);
                                }
                                if (!generateMore) {
                                    $scope.uiState.loadPngImagesInProgress = false;
                                }
                            }).error(function(error) {
                                $scope.showErrorMessage('Failed to generate authentication tokens: ' + error);            
                                $scope.uiState.loadPngImagesInProgress = false;                                                                  
                            });
                        }).error(function(error) {
                            $scope.showErrorMessage('Failed to load image information: ' + error);            
                            $scope.uiState.loadPngImagesInProgress = false;                                      
                        });

                    }

                });
            }).error(function(reason) {
                $scope.showErrorMessage('Failed to load images for series: ' + reason);          
                $scope.uiState.loadPngImagesInProgress = false;              
            });

        }
    };

    $scope.loadSelectedSeriesDatasets = function() {
        if ($scope.uiState.selectedSeries === null) {
            return [];
        }

        var loadDatasetsPromise = $http.get('/api/series/datasets?seriesid=' + $scope.uiState.selectedSeries.id);

        loadDatasetsPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load datasets: ' + error);
        });

        return loadDatasetsPromise;
    };

    // Private functions

    function capitalizeFirst(string) {
        return string.charAt(0).toUpperCase() + string.substring(1);        
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
                $scope.showErrorMessage('Failed to delete patient: ' + error);
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
                $scope.showErrorMessage('Failed to delete study: ' + error);
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
                $scope.showErrorMessage('Failed to delete series: ' + error);
            });
        });

        return $q.all(deletePromises);
    }

    function confirmSendSeries(series) {
        return $mdDialog.show({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                scope: $scope.$new(),
                locals: {
                    title: 'Send ' + series.length + ' Series',
                    receiversCallback: function(startIndex, count, orderByProperty, orderByDirection) {
                        return $http.get('/api/boxes');
                    },
                    sendCallback: function(remoteBoxId) {
                        return sendSeries(remoteBoxId, series);
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

    function confirmSendSeriesToScp(series) {
        return $mdDialog.show({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                scope: $scope.$new(),
                locals: {
                    title: 'Send ' + series.length + ' Series',
                    receiversCallback: function(startIndex, count, orderByProperty, orderByDirection) {
                        return $http.get('/api/scus');
                    },
                    sendCallback: function(scuId) {
                            return sendSeriesToScp(scuId, series);
                        }
                }
            });
    }

    function sendSeriesToScp(scuId, series) {
        return $http.post('/api/scus/' + scuId + '/sendseries/' + series[0].id);
    }

    function confirmSendStudies(studies) {
        return $mdDialog.show({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                scope: $scope.$new(),
                locals: {
                    title: 'Send ' + studies.length + ' Studies',
                    receiversCallback: function(startIndex, count, orderByProperty, orderByDirection) {
                        return $http.get('/api/boxes');
                    },
                    sendCallback: function(remoteBoxId) {
                            return sendStudies(remoteBoxId, studies);
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
        return $mdDialog.show({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                scope: $scope.$new(),
                locals: {
                    title: 'Send ' + patients.length + ' Patients',
                    receiversCallback: function(startIndex, count, orderByProperty, orderByDirection) {
                        return $http.get('/api/boxes');
                    },
                    sendCallback: function(remoteBoxId) {
                            return sendPatients(remoteBoxId, patients);
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

.controller('SendImageFilesModalCtrl', function($scope, $mdDialog, $http, $q, title, receiversCallback, sendCallback) {
    // Initialization
    $scope.title = title;
    $scope.loadReceivers = receiversCallback;

    $scope.uiState.selectedReceiver = null;

    // Scope functions
    $scope.receiverSelected = function(receiver) {
        $scope.uiState.selectedReceiver = receiver;
    };

    $scope.sendButtonClicked = function() {
        var sendPromise = sendCallback($scope.uiState.selectedReceiver.id);

        $scope.uiState.sendInProgress = true;

        sendPromise.error(function(data) {
            $scope.showErrorMessage('Failed to send: ' + data);
        });

        sendPromise.then(function() {
            $mdDialog.hide();
        });

        sendPromise.finally(function() {
            $scope.uiState.sendInProgress = false;
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

});