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

.controller('HomeCtrl', function($scope, $http, $mdDialog, $q, openConfirmActionModal) {
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
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizePatients
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
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizeStudies
            }
        ];

    $scope.seriesActions =
        [
            {
                name: 'Send',
                action: confirmSendSeries
            },
            {
                name: 'Send to PACS',
                requiredSelectionCount: 1,
                action: confirmSendSeriesToScp
            },   
            {
                name: 'Delete',
                action: confirmDeleteSeries
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizeSeries
            }
        ];

    $scope.callbacks = {};

    $scope.uiState = {};
    $scope.uiState.sources = [ { sourceType: null, sourceName: 'None' } ];
    $scope.uiState.selectedSource = null;
    $scope.uiState.selectedPatient = null;
    $scope.uiState.selectedStudy = null;
    $scope.uiState.selectedSeries = null;
    $scope.uiState.loadPngImagesInProgress = false;
    $scope.uiState.seriesDetails = {
        leftColumnSelectedTabIndex: 0,
        rightColumnSelectedTabIndex: 0,
        selectedSeriesSource: "",
        pngImageUrls: [],
        imageHeight: 0,
        images: 1,
        isWindowManual: false,
        windowMin: 0,
        windowMax: 100
    };

    // Scope functions

    $scope.uiState.sourcesPromise = $http.get('/api/metadata/sources').then(function(sourcesData) {
        angular.forEach(sourcesData.data, function(source) {
            $scope.uiState.sources.push(source);
        });
        return $scope.uiState.sources;
    });

    $scope.nameForSource = function(source) {
        return source.sourceType === null ? "No filter" : source.sourceName + " (" + source.sourceType + ")";        
    };

    $scope.loadPatients = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadPatientsUrl = '/api/metadata/patients?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            var orderByPropertyName = orderByProperty === "id" ? orderByProperty : capitalizeFirst(orderByProperty.substring(0, orderByProperty.indexOf('[')));
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

        loadPatientsUrl = urlWithSourceQuery(loadPatientsUrl);

        var loadPatientsPromise = $http.get(loadPatientsUrl);

        loadPatientsPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load patients: ' + error);
        });

        return loadPatientsPromise;
    };

    $scope.sourceSelected = function() {
        $scope.patientSelected(null);        
        $scope.callbacks.patientsTable.reset();
        if ($scope.callbacks.flatSeriesTable) {
            $scope.callbacks.flatSeriesTable.reset();
        }
    };

    $scope.patientSelected = function(patient) {
        if ($scope.uiState.selectedPatient !== patient) {
            $scope.uiState.selectedPatient = patient;
            $scope.studySelected(null, true);
        }
    };

    $scope.loadStudies = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedPatient === null) {
            return [];
        }

        var loadStudiesUrl = '/api/metadata/studies?startindex=' + startIndex + '&count=' + count + '&patientid=' + $scope.uiState.selectedPatient.id;

        loadStudiesUrl = urlWithSourceQuery(loadStudiesUrl);

        var loadStudiesPromise = $http.get(loadStudiesUrl);

        loadStudiesPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load studies: ' + error);
        });

        return loadStudiesPromise;
    };

    $scope.studySelected = function(study, reset) {
        if ($scope.uiState.selectedStudy !== study) {
            $scope.uiState.selectedStudy = study;
            $scope.seriesSelected(null, true);
        }
        if (reset && $scope.callbacks.studiesTable) {
            $scope.callbacks.studiesTable.reset();
        }
    };

    $scope.loadSeries = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedStudy === null) {
            return [];
        }

        var loadSeriesUrl = '/api/metadata/series?startindex=' + startIndex + '&count=' + count + '&studyid=' + $scope.uiState.selectedStudy.id;

        loadSeriesUrl = urlWithSourceQuery(loadSeriesUrl);

        var loadSeriesPromise = $http.get(loadSeriesUrl);

        loadSeriesPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load series: ' + error);
        });

        return loadSeriesPromise;
    };

    $scope.loadFlatSeries = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadFlatSeriesUrl = '/api/metadata/flatseries?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            var orderByPropertyName = orderByProperty == "id" ? orderByProperty : capitalizeFirst(orderByProperty.substring(orderByProperty.indexOf('.') + 1, orderByProperty.indexOf('[')));
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

        loadFlatSeriesUrl = urlWithSourceQuery(loadFlatSeriesUrl);

        var loadFlatSeriesPromise = $http.get(loadFlatSeriesUrl);

        loadFlatSeriesPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load series: ' + error);
        });

        return loadFlatSeriesPromise;
    };

    $scope.seriesSelected = function(series, reset) {
        if ($scope.uiState.selectedSeries !== series) {
            $scope.uiState.selectedSeries = series;

            $scope.uiState.seriesDetails.selectedSeriesSource = "";
            $scope.uiState.seriesDetails.pngImageUrls = [];

            if ($scope.callbacks.imageAttributesTable) { 
                $scope.callbacks.imageAttributesTable.reset(); 
            }
            if ($scope.callbacks.datasetsTable) { 
                $scope.callbacks.datasetsTable.reset();
            }

            $scope.updatePNGImageUrls();

            if (series !== null) {
                updateSelectedSeriesSource(series);
            }
        }

        if (reset && $scope.callbacks.seriesTable) {
            $scope.callbacks.seriesTable.reset();
        }
    };

    $scope.flatSeriesSelected = function(flatSeries) {

        if (flatSeries !== null) {
            $scope.patientSelected(flatSeries.patient);
            $scope.studySelected(flatSeries.study);
            $scope.seriesSelected(flatSeries.series);
        }
    };

    $scope.loadImageAttributes = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedSeries === null) {
            return [];
        }

        var imagesPromise = $http.get('/api/metadata/images?count=1&seriesid=' + $scope.uiState.selectedSeries.id);

        imagesPromise.error(function(reason) {
            $scope.showErrorMessage('Failed to load images for series: ' + error);            
        });

        var attributesPromise = imagesPromise.then(function(images) {
            if (images.data.length > 0) {
                return $http.get('/api/images/' + images.data[0].id + '/attributes').then(function(data) {
                    if (orderByProperty) {
                        if (!orderByDirection) {
                            orderByDirection = 'ASCENDING';
                        }
                        return data.data.sort(function compare(a,b) {
                          return orderByDirection === 'ASCENDING' ? 
                            a[orderByProperty] < b[orderByProperty] ? -1 : a[orderByProperty] > b[orderByProperty] ? 1 : 0 :
                            a[orderByProperty] > b[orderByProperty] ? -1 : a[orderByProperty] < b[orderByProperty] ? 1 : 0;
                        });
                    } else {
                        return data.data;
                    }
                }, function(error) {
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

            $http.get('/api/metadata/images?count=1000000&seriesid=' + $scope.uiState.selectedSeries.id).success(function(images) {

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

        var loadDatasetsPromise = $http.get('/api/metadata/images?count=1000000&seriesid=' + $scope.uiState.selectedSeries.id).then(function(images) {
            return images.data.map(function(image) {
                return { url: '/api/images/' + image.id };
            });
        }, function(error) {
            $scope.showErrorMessage('Failed to load datasets: ' + error);
        });

        return loadDatasetsPromise;
    };

    // Private functions

    function imagesForSeries(series) {
        var promises = series.map(function(singleSeries) {
            return $http.get(urlWithSourceQuery('/api/metadata/images?startindex=0&count=1000000&seriesid=' + singleSeries.id)).then(function (imagesData) {
                return imagesData.data;
            });
        });
        return flatten(promises);
    }

    function imagesForStudies(studies) {
        var promises = studies.map(function(study) {
            return $http.get(urlWithSourceQuery('/api/metadata/series?startindex=0&count=1000000&studyid=' + study.id)).then(function (seriesData) {
                return imagesForSeries(seriesData.data);
            });
        });
        return flatten(promises);
    }

    function imagesForPatients(patients) {
        var promises = patients.map(function(patient) {
            return $http.get(urlWithSourceQuery('/api/metadata/studies?startindex=0&count=1000000&patientid=' + patient.id)).then(function (studiesData) {
                return imagesForStudies(studiesData.data);
            });
        });
        return flatten(promises);
    }

    function flatten(promises) {
        return $q.all(promises).then(function (arrayOfImageArrays) {
            return [].concat.apply([], arrayOfImageArrays);
        });                
    }

    function urlWithSourceQuery(url) {
        if ($scope.uiState.selectedSource !== null && $scope.uiState.selectedSource.sourceType !== null) {
            return url + '&sourcetype=' + $scope.uiState.selectedSource.sourceType + '&sourceid=' + $scope.uiState.selectedSource.sourceId;
        } else {
            return url;
        }
    }

    function updateSelectedSeriesSource(series) {
        $scope.uiState.sourcesPromise.then(function(sources) {
            $http.get('/api/metadata/series/' + series.id + '/source').success(function(source) {
                $scope.uiState.seriesDetails.selectedSeriesSource = source.sourceName + " (" + source.sourceType + ")";
            });                
        });        
    }

    function capitalizeFirst(string) {
        return string.charAt(0).toUpperCase() + string.substring(1);        
    }

    function confirmSend(receiversUrl, receiverSelectedCallback) {
        return $mdDialog.show({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SelectReceiverModalCtrl',
                scope: $scope.$new(),
                locals: {
                    receiversUrl: receiversUrl,
                    receiverSelectedCallback: receiverSelectedCallback
                }
            });        
    }

    function confirmSendSeries(series) {
        // check if flat series
        series = series.map(function(s) {
            return s.series ? s.series : s;
        });

        return confirmSend('/api/boxes', function(receiverId) {
            var seriesIds = series.map(function(entity) { 
                return entity.id; 
            });

            var entityIdToPatientPromise = $http.get('/api/metadata/studies/' + series[0].studyId).then(function(study) {
                return $http.get('/api/metadata/patients/' + study.data.patientId);
            }).then(function(patient) {
                var entityIdToPatient = {};
                for (var i = 0; i < seriesIds.length; i++) {
                    entityIdToPatient[seriesIds[i]] = patient.data;                    
                }
                return entityIdToPatient;
            });

            return showBoxSendTagValuesModal("series", entityIdToPatientPromise, receiverId, 'sendseries');
        });
    }

    function confirmSendStudies(studies) {
        return confirmSend('/api/boxes', function(receiverId) {
            var studyIds = studies.map(function(entity) { 
                return entity.id; 
            });

            var entityIdToPatientPromise = $http.get('/api/metadata/patients/' + studies[0].patientId).then(function(patient) {
                var entityIdToPatient = {};
                for (var i = 0; i < studyIds.length; i++) {
                    entityIdToPatient[studyIds[i]] = patient.data;                    
                }
                return entityIdToPatient;
            });

            return showBoxSendTagValuesModal("study(s)", entityIdToPatientPromise, receiverId, 'sendstudies');
        });
    }

    function confirmSendPatients(patients) {
        return confirmSend('/api/boxes', function(receiverId) {

            var entityIdToPatient = {};
            for (var i = 0; i < patients.length; i++) {
                entityIdToPatient[patients[i].id] = patients[i];
            }

            var entityIdToPatientPromise = $q.when(entityIdToPatient);

            return showBoxSendTagValuesModal("patient(s)", entityIdToPatientPromise, receiverId, 'sendpatients');
        });
    }

    function confirmSendSeriesToScp(series) {
        // check if flat series
        series = series.map(function(s) {
            return s.series ? s.series : s;
        });

        return confirmSend('/api/scus', function(receiverId) {
            return $http.post('/api/scus/' + receiverId + '/sendseries/' + series[0].id).success(function() {
                $mdDialog.hide();
                $scope.showInfoMessage("Series sent to PACS");
            }).error(function(data) {
                $scope.showErrorMessage('Failed to send to PACS: ' + data);
            });
        });
    }

    function confirmDeletePatients(patients) {
        imagesForPatients(patients).then(function(images) {
            var f = $scope.confirmDeleteEntitiesFunction('/api/images/', 'images');
            f(images).finally(function() {
                $scope.patientSelected(null);        
                $scope.callbacks.patientsTable.reset();
            });
        });
    }

    function confirmDeleteStudies(studies) {
        imagesForStudies(studies).then(function(images) {
            var f = $scope.confirmDeleteEntitiesFunction('/api/images/', 'images');
            f(images).finally(function() {
                $scope.studySelected(null);        
                $scope.callbacks.studiesTable.reset();
            });
        });
    }

    function confirmDeleteSeries(series) {
        imagesForSeries(series).then(function(images) {
            var f = $scope.confirmDeleteEntitiesFunction('/api/images/', 'images');
            f(images).finally(function() {
                if ($scope.callbacks.flatSeriesTable) {
                    $scope.flatSeriesSelected(null);     
                    $scope.callbacks.flatSeriesTable.reset();
                } 
                if ($scope.callbacks.seriesTable) {
                    $scope.seriesSelected(null);        
                    $scope.callbacks.seriesTable.reset();
                }
            });
        });
    }

    function confirmAnonymizePatients(patients) {
        imagesForPatients(patients).then(function(images) {
            openConfirmActionModal('Anonymize', 'Force anonymization of ' + images.length + ' images? Patient information will be lost.', 'Anonymize', function() {
                var promises = images.forEach(function(image) {
                    return $http.post('/api/images/' + image.id + '/anonymize');
                });
                return $q.all(promises).finally(function() {
                    $scope.patientSelected(null);        
                    $scope.callbacks.patientsTable.reset();
                    if ($scope.callbacks.flatSeriesTable) {
                        $scope.callbacks.flatSeriesTable.reset();
                    }                
                });
            });
        });
    }

    function confirmAnonymizeStudies(studies) {
    } 

    function confirmAnonymizeSeries(series) {
    }

    function showBoxSendTagValuesModal(text, entityIdToPatientPromise, receiverId, sendCommand) {
        return $mdDialog.show({
                templateUrl: '/assets/partials/boxSendTagValuesModalContent.html',
                controller: 'BoxSendTagValuesCtrl',
                scope: $scope.$new(),
                locals: {
                    text: text,
                    entityIdToPatient: entityIdToPatientPromise,
                    receiverId: receiverId,
                    sendCommand: sendCommand
                }
        });                
    }
})

.controller('SelectReceiverModalCtrl', function($scope, $mdDialog, $http, receiversUrl, receiverSelectedCallback) {
    // Initialization
    $scope.title = 'Select Receiver';

    $scope.uiState.selectedReceiver = null;

    // Scope functions
    $scope.loadReceivers = function() {
        return $http.get(receiversUrl);
    };

    $scope.receiverSelected = function(receiver) {
        $scope.uiState.selectedReceiver = receiver;
    };

    $scope.selectButtonClicked = function() {
        receiverSelectedCallback($scope.uiState.selectedReceiver.id);
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

})

.controller('BoxSendTagValuesCtrl', function($scope, $mdDialog, $http, text, entityIdToPatient, receiverId, sendCommand) {
    // Initialization
    $scope.title = 'Anonymization Options';

    // get unique patients and an their respective indices
    $scope.patients = [];
    var entityIds = [];
    for (var entityId in entityIdToPatient) {
        entityIds.push(parseInt(entityId));
        if (entityIdToPatient.hasOwnProperty(entityId)) {
            var patient = entityIdToPatient[entityId];
            if ($scope.patients.indexOf(patient) < 0) {
                $scope.patients.push(patient);
            }
        }
    }

    $scope.namePrefix = "anon";
    $scope.numberingLength = 3;
    $scope.numberingStart = 1;

    $scope.listAttributes = function(startIndex, count, orderByProperty, orderByDirection) {
        return $scope.patients;
    };

    $scope.updateAnonymousPatientNames = function() {
        $scope.anonymizedPatientNames = $scope.patients.map(function(patient, index) {
            return $scope.namePrefix + " " + zeroPad($scope.numberingStart + index, $scope.numberingLength);                
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

    $scope.sendButtonClicked = function() {
        var tagValues = [];
        for (var i = 0; i < entityIds.length; i++) {
            var entityId = entityIds[i];
            var patient = entityIdToPatient[entityId];
            var patientIndex = $scope.patients.indexOf(patient);
            var anonName = $scope.anonymizedPatientNames[patientIndex];
            tagValues.push( { entityId: entityId, tag: 0x00100010, value: anonName } );
        }

        var sendData = { 
            entityIds: entityIds, 
            tagValues: tagValues
        };

        var sendPromise = $http.post('/api/boxes/' + receiverId + '/' + sendCommand, sendData );

        $scope.uiState.sendInProgress = true;

        sendPromise.then(function(data) {
            $mdDialog.hide();
            $scope.showInfoMessage(entityIds.length + " " + text + " added to outbox");
        }, function(data) {
            $scope.showErrorMessage('Failed to send: ' + data);
        });

        sendPromise.finally(function() {
            $scope.uiState.sendInProgress = false;
        });
    };

    function zeroPad(num, length) {
        var an = Math.abs(num);
        var digitCount = 1 + Math.floor(Math.log(an) / Math.LN10);
        if (digitCount >= length) {
            return num;
        }
        var zeroString = Math.pow(10, length - digitCount).toString().substr(1);
        return num < 0 ? '-' + zeroString + an : zeroString + an;
    }

    $scope.updateAnonymousPatientNames();
    
});