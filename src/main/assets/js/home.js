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

.controller('HomeCtrl', function($scope, $http, $mdDialog) {
    // Initialization
    $scope.patientActions =
        [
            {
                name: 'Send',
                action: confirmSendPatients
            },
            {
                name: 'Delete',
                action: $scope.confirmDeleteEntitiesFunction('/api/metadata/patients/', 'patient(s)')
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
                action: $scope.confirmDeleteEntitiesFunction('/api/metadata/studies/', 'study(s)')
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
                action: $scope.confirmDeleteEntitiesFunction('/api/metadata/series/', 'series')
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
        windowMin: undefined,
        windowMax: undefined
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

            resetSeriesDetails();

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

                // assume all images have the same number of frames etc so we get the image information for the first image only
                if (images.length > 0) {
                    $http.get('/api/images/' + images[0].id + '/imageinformation').success(function(info) {
                        if (!$scope.uiState.seriesDetails.isWindowManual || $scope.uiState.seriesDetails.windowMin === undefined || $scope.uiState.seriesDetails.windowMax === undefined) {
                            $scope.uiState.seriesDetails.windowMin = info.minimumPixelValue;
                            $scope.uiState.seriesDetails.windowMax = info.maximumPixelValue;
                        }
                        var n = Math.min($scope.uiState.seriesDetails.images, info.numberOfFrames * images.length);
                        $http.post('/api/users/generateauthtokens?n=' + n).success(function(tokens) {
                            $scope.uiState.seriesDetails.pngImageUrls = [];
                            $scope.uiState.loadPngImagesInProgress = false;
                            var tokenIndex = 0;
                            var nImages = Math.max(1, Math.min(images.length, Math.ceil(n / info.numberOfFrames)));
                            for (var j = 0; j < nImages; j++) {
                                var image = images[j];
                                var nFrames = Math.min(n - j*info.numberOfFrames, info.numberOfFrames);
                                for (var i = 0; i < nFrames; i++) {
                                    var url = '/api/images/' + image.id + '/png'+ '?authtoken=' + tokens[tokenIndex].token + '&framenumber=' + (i + 1);
                                    if ($scope.uiState.seriesDetails.isWindowManual) {
                                        url = url + 
                                            '&windowmin=' + $scope.uiState.seriesDetails.windowMin + 
                                            '&windowmax=' + $scope.uiState.seriesDetails.windowMax;
                                    }
                                    if (!isNaN(parseInt($scope.uiState.seriesDetails.imageHeight))) {
                                        url = url + 
                                            '&imageheight=' + $scope.uiState.seriesDetails.imageHeight;
                                    }
                                    $scope.uiState.seriesDetails.pngImageUrls.push({ url: url, frameIndex: info.frameIndex });
                                    tokenIndex += 1;
                                }
                            }
                            $scope.uiState.loadPngImagesInProgress = false;                            
                        }).error(function(error) {
                            $scope.showErrorMessage('Failed to generate authentication tokens: ' + error);            
                            $scope.uiState.loadPngImagesInProgress = false;                                                                  
                        });
                    }).error(function(error) {
                        $scope.showErrorMessage('Failed to load image information: ' + error);            
                        $scope.uiState.loadPngImagesInProgress = false;                                      
                    });

                }

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

    function resetSeriesDetails() {
        $scope.uiState.seriesDetails.pngImageUrls = [];
        $scope.uiState.seriesDetails.pngImageUrls = [];
        $scope.uiState.seriesDetails.windowMin = undefined;
        $scope.uiState.seriesDetails.windowMax = undefined;
    }

    function capitalizeFirst(string) {
        return string.charAt(0).toUpperCase() + string.substring(1);        
    }

    function confirmSend(entities, entitiesText, receiversUrl, sendFunction) {
        return $mdDialog.show({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                scope: $scope.$new(),
                locals: {
                    text: entities.length + ' ' + entitiesText,
                    receiversCallback: function(startIndex, count, orderByProperty, orderByDirection) {
                        return $http.get(receiversUrl);
                    },
                    sendCallback: function(receiverId) {
                        return sendFunction(receiverId, entities);
                    }
                }
            });        
    }

    function boxSendFunction(sendCommand) {
        return function(receiverId, entities) {
            var entityIds = [];

            angular.forEach(entities, function(entity) {
                entityIds.push(entity.id);
            });

            return $http.post('/api/boxes/' + receiverId + '/' + sendCommand, entityIds);        
        };
    }

    function scuSendFunction() {
        return function(receiverId, entities) {
            return $http.post('/api/scus/' + receiverId + '/sendseries/' + entities[0].id);
        };
    }

    function confirmSendSeries(series) {
        return confirmSend(series, 'series', '/api/boxes', boxSendFunction('sendseries'));
    }

    function confirmSendStudies(studies) {
        return confirmSend(studies, 'study(s)', '/api/boxes', boxSendFunction('sendstudies'));
    }

    function confirmSendPatients(patients) {
        return confirmSend(patients, 'patient(s)', '/api/boxes', boxSendFunction('sendpatients'));
    }

    function confirmSendSeriesToScp(series) {
        return confirmSend(series, 'series', '/api/scus', scuSendFunction());
    }

})

.controller('SendImageFilesModalCtrl', function($scope, $mdDialog, $http, text, receiversCallback, sendCallback) {
    // Initialization
    $scope.title = 'Send ' + text + '?';
    $scope.loadReceivers = receiversCallback;

    $scope.uiState.selectedReceiver = null;

    // Scope functions
    $scope.receiverSelected = function(receiver) {
        $scope.uiState.selectedReceiver = receiver;
    };

    $scope.sendButtonClicked = function() {
        var sendPromise = sendCallback($scope.uiState.selectedReceiver.id);

        $scope.uiState.sendInProgress = true;

        sendPromise.then(function() {
            $mdDialog.hide();
            $scope.showInfoMessage(text + " added to outbox");
        }, function(data) {
            $scope.showErrorMessage('Failed to send: ' + data);
        });

        sendPromise.finally(function() {
            $scope.uiState.sendInProgress = false;
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

});