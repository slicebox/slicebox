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

.controller('HomeCtrl', function($scope, $http, $mdDialog, $q, openConfirmActionModal, openTagSeriesModal, sbxMisc, sbxMetaData, sbxToast) {

    // Initialization

    $scope.patientActions =
        [
            {
                name: 'Send',
                action: confirmSendPatients
            },
            {
                name: 'Send to SCP',
                action: confirmSendPatientsToScp
            },
            {
                name: 'Delete',
                action: confirmDeletePatients
            },
            {
                name: 'Tag',
                action: tagSeriesForPatients
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizePatients
            },
            {
                name: 'Export',
                action: confirmExportPatients
            }
        ];

    $scope.studyActions =
        [
            {
                name: 'Send',
                action: confirmSendStudies
            },
            {
                name: 'Send to SCP',
                action: confirmSendStudiesToScp
            },
            {
                name: 'Delete',
                action: confirmDeleteStudies
            },
            {
                name: 'Tag',
                action: tagSeriesForStudies
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizeStudies
            },
            {
                name: 'Export',
                action: confirmExportStudies
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
                action: confirmSendSeriesToScp
            },   
            {
                name: 'Delete',
                action: confirmDeleteSeries
            },
            {
                name: 'Tag',
                action: tagSeries
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizeSeries
            },
            {
                name: 'Export',
                action: confirmExportSeries
            }
        ];

    $scope.imageAttributesActions =
        [
            {
                name: 'Add Series Type Rule',
                action: addSeriesTypeRule
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
        selectedSeriesSource: "",
        selectedSeriesSeriesTypes: [],
        selectedSeriesSeriesTags: [],
        pngImageUrls: [],
        imageHeight: 0,
        images: 1,
        isWindowManual: false,
        windowMin: 0,
        windowMax: 100,
        tagState: {
            searchText: ""
        }
    };
    $scope.uiState.advancedFiltering = {
        sourcesPromise: $q.when([]),
        seriesTypesPromise: $q.when([]),
        seriesTagsPromise: $q.when([]),
        selectedSources: [],
        selectedSeriesTypes: [],
        selectedSeriesTags: []
    };

    $scope.uiState.advancedFiltering.sourcesPromise = $http.get('/api/sources').then(function(sourcesData) {
        return sourcesData.data.map(function (source) {
            source.selected = false;
            return source;
        }); 
    });

    $scope.uiState.advancedFiltering.seriesTypesPromise = $http.get('/api/seriestypes').then(function (seriesTypesData) {        
        return seriesTypesData.data.map(function (seriesType) {
            seriesType.selected = false;
            return seriesType;
        }); 
    });
    
    updateSeriesTagsPromise();

    // Scope functions

    $scope.findSeriesTags = function(searchText) {
        var lcSearchText = angular.lowercase(searchText);
        var selectedTagNames = $scope.uiState.seriesDetails.selectedSeriesSeriesTags.map(function (seriesTag) { return seriesTag.name; });
        return searchText ? $scope.uiState.advancedFiltering.seriesTagsPromise.then(function (seriesTags) { 
            return seriesTags.filter(function (seriesTag) {
                var lcName = angular.lowercase(seriesTag.name);
                return lcName.indexOf(lcSearchText) === 0;
            }).filter(function (seriesTag) {
                return selectedTagNames.indexOf(seriesTag.name) < 0;
            });
        }) : [];
    };

    $scope.seriesTagAdded = function(tag) {
        var theTag = tag.name ? tag : { id: -1, name: tag };
        $http.post('/api/metadata/series/' + $scope.uiState.selectedSeries.id + '/seriestags', theTag).success(function (addedTag) {
            // copy database id to selected tag
            $scope.uiState.seriesDetails.selectedSeriesSeriesTags.forEach(function (selectedTag) {
                if (selectedTag.name === addedTag.name) {
                    selectedTag.id = addedTag.id;
                }
            });
            if ($scope.uiState.selectedSeries) {
                updateSelectedSeriesSeriesTags($scope.uiState.selectedSeries);
            }
            updateSeriesTagsPromise();
        });
        return theTag;
    };

    $scope.seriesTagRemoved = function(tag) {
        $http.delete('/api/metadata/series/' + $scope.uiState.selectedSeries.id + '/seriestags/' + tag.id).success(function () {
            updateSeriesTagsPromise();
        });
    };

    $scope.nameForSource = function(source) {
        return source.sourceName + " (" + source.sourceType + ")";        
    };

    $scope.loadPatients = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        if ($scope.uiState.selectedPatient) {
            return $q.when([ $scope.uiState.selectedPatient ]);
        } else {
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

            loadPatientsUrl = urlWithAdvancedFiltering(loadPatientsUrl);

            var loadPatientsPromise = $http.get(loadPatientsUrl);

            loadPatientsPromise.error(function(error) {
                sbxToast.showErrorMessage('Failed to load patients: ' + error);
            });

            return loadPatientsPromise;
        }
    };

    $scope.patientSelected = function(patient) {
        if ($scope.uiState.selectedPatient !== patient) {
            $scope.uiState.selectedPatient = patient;
            $scope.studySelected(null, true);
        }
        $scope.callbacks.patientsTable.reloadPage();
    };

    $scope.loadStudies = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedPatient === null) {
            return [];
        }

        if ($scope.uiState.selectedStudy) {
            return $q.when([ $scope.uiState.selectedStudy ]);
        } else {
            var loadStudiesUrl = '/api/metadata/studies?startindex=' + startIndex + '&count=' + count + '&patientid=' + $scope.uiState.selectedPatient.id;

            loadStudiesUrl = urlWithAdvancedFiltering(loadStudiesUrl);

            var loadStudiesPromise = $http.get(loadStudiesUrl);

            loadStudiesPromise.error(function(error) {
                sbxToast.showErrorMessage('Failed to load studies: ' + error);
            });

            return loadStudiesPromise;
        }
    };

    $scope.studySelected = function(study, reset) {
        if ($scope.uiState.selectedStudy !== study) {
            $scope.uiState.selectedStudy = study;
            $scope.seriesSelected(null, true);
        }
        if (reset && $scope.callbacks.studiesTable) {
            $scope.callbacks.studiesTable.reset();
        } else if ($scope.callbacks.studiesTable) {
            $scope.callbacks.studiesTable.reloadPage();
        }
    };

    $scope.loadSeries = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedStudy === null) {
            return [];
        }

        var loadSeriesUrl = '/api/metadata/series?startindex=' + startIndex + '&count=' + count + '&studyid=' + $scope.uiState.selectedStudy.id;

        loadSeriesUrl = urlWithAdvancedFiltering(loadSeriesUrl);

        var loadSeriesPromise = $http.get(loadSeriesUrl);

        loadSeriesPromise.error(function(error) {
            sbxToast.showErrorMessage('Failed to load series: ' + error);
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

        loadFlatSeriesUrl = urlWithAdvancedFiltering(loadFlatSeriesUrl);

        var loadFlatSeriesPromise = $http.get(loadFlatSeriesUrl);

        loadFlatSeriesPromise.error(function(error) {
            sbxToast.showErrorMessage('Failed to load series: ' + error);
        });

        return loadFlatSeriesPromise;
    };

    $scope.seriesSelected = function(series, reset) {
        if ($scope.uiState.selectedSeries !== series) {
            $scope.uiState.selectedSeries = series;

            $scope.uiState.seriesDetails.selectedSeriesSource = "";
            $scope.uiState.seriesDetails.selectedSeriesSeriesTypes = [];
            $scope.uiState.seriesDetails.selectedSeriesSeriesTags = [];
            $scope.uiState.seriesDetails.pngImageUrls = [];
            $scope.uiState.seriesDetails.tagState.searchText = "";

            if ($scope.callbacks.imageAttributesTable) { 
                $scope.callbacks.imageAttributesTable.reset(); 
            }
            if ($scope.callbacks.datasetsTable) { 
                $scope.callbacks.datasetsTable.reset();
            }

            $scope.updatePNGImageUrls();

            if (series !== null) {
                updateSelectedSeriesSource(series);
                updateSelectedSeriesSeriesTypes(series);
                updateSelectedSeriesSeriesTags(series);
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
        } else {
            $scope.seriesSelected(null);
        }
    };

    $scope.loadImageAttributes = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedSeries === null) {
            return [];
        }

        var imagesPromise = $http.get('/api/metadata/images?count=1&seriesid=' + $scope.uiState.selectedSeries.id);

        imagesPromise.error(function(reason) {
            sbxToast.showErrorMessage('Failed to load images for series: ' + error);            
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
                    sbxToast.showErrorMessage('Failed to load image attributes: ' + error);
                });
            } else {
                return [];
            }
        });

        return attributesPromise;
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
            sbxToast.showErrorMessage('Failed to load datasets: ' + error);
        });

        return loadDatasetsPromise;
    };

    $scope.openAdvancedFilteringModal = function() {
        var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/advancedFilteringModalContent.html',
            controller: 'AdvancedFilteringModalCtrl',
            locals: {
                sources: $scope.uiState.advancedFiltering.sourcesPromise,
                seriesTypes: $scope.uiState.advancedFiltering.seriesTypesPromise,
                seriesTags: $scope.uiState.advancedFiltering.seriesTagsPromise
            },
            scope: $scope.$new()
        });

        dialogPromise.then(function (selections) {
            $scope.uiState.advancedFiltering.selectedSources = selections.selectedSources;
            $scope.uiState.advancedFiltering.selectedSeriesTypes = selections.selectedSeriesTypes;
            $scope.uiState.advancedFiltering.selectedSeriesTags = selections.selectedSeriesTags;

            $scope.patientSelected(null);        
            $scope.callbacks.patientsTable.reset();
            if ($scope.callbacks.flatSeriesTable) {
                $scope.callbacks.flatSeriesTable.reset();
            }
        });
        return dialogPromise;
    };

    $scope.openImageSettingsModal = function() {
        var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/imageSettingsModalContent.html',
            controller: 'ImageSettingsModalCtrl',
            locals: { 
                imageHeight: $scope.uiState.seriesDetails.imageHeight, 
                images: $scope.uiState.seriesDetails.images, 
                isWindowManual: $scope.uiState.seriesDetails.isWindowManual, 
                windowMin: $scope.uiState.seriesDetails.windowMin, 
                windowMax: $scope.uiState.seriesDetails.windowMax },
            scope: $scope.$new()
        });

        dialogPromise.then(function (settings) {
            angular.extend($scope.uiState.seriesDetails, settings);
            $scope.updatePNGImageUrls();
        });
        return dialogPromise;
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
                                sbxToast.showErrorMessage('Failed to generate authentication tokens: ' + error);            
                                $scope.uiState.loadPngImagesInProgress = false;                                                                  
                            });
                        }).error(function(error) {
                            sbxToast.showErrorMessage('Failed to load image information: ' + error);            
                            $scope.uiState.loadPngImagesInProgress = false;                                      
                        });

                    }

                });
            }).error(function(reason) {
                sbxToast.showErrorMessage('Failed to load images for series: ' + reason);          
                $scope.uiState.loadPngImagesInProgress = false;              
            });

        }
    };

    // Private functions

    function urlWithAdvancedFiltering(url) {
        return sbxMetaData.urlWithAdvancedFiltering(
            url, 
            $scope.uiState.advancedFiltering.selectedSources, 
            $scope.uiState.advancedFiltering.selectedSeriesTypes, 
            $scope.uiState.advancedFiltering.selectedSeriesTags);        
    }

    function imagesForPatients(patients) {
        return sbxMetaData.imagesForPatients(
            patients,
            $scope.uiState.advancedFiltering.selectedSources, 
            $scope.uiState.advancedFiltering.selectedSeriesTypes, 
            $scope.uiState.advancedFiltering.selectedSeriesTags);
    }

    function imagesForStudies(studies) {
        return sbxMetaData.imagesForStudies(
            studies,
            $scope.uiState.advancedFiltering.selectedSources, 
            $scope.uiState.advancedFiltering.selectedSeriesTypes, 
            $scope.uiState.advancedFiltering.selectedSeriesTags);
    }

    function imagesForSeries(series) {
        return sbxMetaData.imagesForSeries(
            series,
            $scope.uiState.advancedFiltering.selectedSources, 
            $scope.uiState.advancedFiltering.selectedSeriesTypes, 
            $scope.uiState.advancedFiltering.selectedSeriesTags);
    }

    function seriesForPatients(patients) {
        return sbxMetaData.seriesForPatients(
            patients,
            $scope.uiState.advancedFiltering.selectedSources, 
            $scope.uiState.advancedFiltering.selectedSeriesTypes, 
            $scope.uiState.advancedFiltering.selectedSeriesTags);
    }

    function seriesForStudies(studies) {
        return sbxMetaData.seriesForStudies(
            studies,
            $scope.uiState.advancedFiltering.selectedSources, 
            $scope.uiState.advancedFiltering.selectedSeriesTypes, 
            $scope.uiState.advancedFiltering.selectedSeriesTags);
    }

    function updateSeriesTagsPromise() {
        $scope.uiState.advancedFiltering.seriesTagsPromise = $scope.uiState.advancedFiltering.seriesTagsPromise.then(function (oldTags) {
            return $http.get('/api/metadata/seriestags').then(function (newTagsData) {        
                var newTags = newTagsData.data;
                // copy selected attribute from existing tags
                newTags.forEach(function (newTag) {
                    newTag.selected = false;
                    oldTags.forEach(function (oldTag) {
                        if (newTag.name === oldTag.name) {
                            newTag.selected = oldTag.selected;
                        }
                    });
                }); 
                return newTags;
            });
        });
    }

    function updateSelectedSeriesSource(series) {
        return $http.get('/api/metadata/series/' + series.id + '/source').success(function (source) {
            $scope.uiState.seriesDetails.selectedSeriesSource = source.sourceName + " (" + source.sourceType + ")";
        });
    }

    function updateSelectedSeriesSeriesTypes(series) {
        return $http.get('/api/metadata/series/' + series.id + '/seriestypes').success(function (seriesTypes) {
            $scope.uiState.seriesDetails.selectedSeriesSeriesTypes = seriesTypes;
        });
    }

    function updateSelectedSeriesSeriesTags(series) {
        return $http.get('/api/metadata/series/' + series.id + '/seriestags').success(function (seriesTags) {
            $scope.uiState.seriesDetails.selectedSeriesSeriesTags = seriesTags;
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

    function confirmSendPatients(patients) {
        return confirmSend('/api/boxes', function(receiverId) {
            var imageIdToPatientPromise = createImageIdToPatientPromiseForPatients(patients);

            return showBoxSendTagValuesModal(imageIdToPatientPromise, function(imageTagValuesSeq) {
                return $http.post('/api/boxes/' + receiverId + '/send', imageTagValuesSeq);
            }, "sent", "send");
        });
    }

    function confirmSendStudies(studies) {
        return confirmSend('/api/boxes', function(receiverId) {
            var imageIdToPatientPromise = createImageIdToPatientPromiseForStudies(studies);

            return showBoxSendTagValuesModal(imageIdToPatientPromise, function (imageTagValuesSeq) {
                return $http.post('/api/boxes/' + receiverId + '/send', imageTagValuesSeq);
            }, "sent", "send");
        });
    }

    function confirmSendSeries(series) {
        // check if flat series
        series = series.map(function(s) {
            return s.series ? s.series : s;
        });

        return confirmSend('/api/boxes', function(receiverId) {
            var imageIdToPatientPromise = createImageIdToPatientPromiseForSeries(series);

            return showBoxSendTagValuesModal(imageIdToPatientPromise, function(imageTagValuesSeq) {
                return $http.post('/api/boxes/' + receiverId + '/send', imageTagValuesSeq);
            }, "sent", "send");
        });
    }

    function confirmSendPatientsToScp(patients) {
        imagesForPatients(patients).then(function (images) { confirmSendToScp(images); });
    }

    function confirmSendStudiesToScp(studies) {
        imagesForStudies(studies).then(function (images) { confirmSendToScp(images); });
    }

    function confirmSendSeriesToScp(series) {
        imagesForSeries(series).then(function (images) { confirmSendToScp(images); });
    }

    function confirmSendToScp(images) {
        return confirmSend('/api/scus', function(receiverId) {
            var imageIds = images.map(function (image) { return image.id; });
            return $http.post('/api/scus/' + receiverId + '/send', imageIds).success(function() {
                $mdDialog.hide();
                sbxToast.showInfoMessage("Series sent to SCP");
            }).error(function(data) {
                sbxToast.showErrorMessage('Failed to send to SCP: ' + data);
            });
        });
    }

    function confirmDeletePatients(patients) {
        imagesForPatients(patients).then(function(images) {
            var f = openDeleteEntitiesModalFunction('/api/images/', 'images');
            f(images).finally(function() {
                $scope.patientSelected(null);        
                $scope.callbacks.patientsTable.reset();
                updateSeriesTagsPromise();
            });
        });
    }

    function confirmDeleteStudies(studies) {
        imagesForStudies(studies).then(function(images) {
            var f = openDeleteEntitiesModalFunction('/api/images/', 'images');
            f(images).finally(function() {
                $scope.studySelected(null);        
                $scope.callbacks.studiesTable.reset();
                updateSeriesTagsPromise();
            });
        });
    }

    function confirmDeleteSeries(series) {
        imagesForSeries(series).then(function(images) {
            var f = openDeleteEntitiesModalFunction('/api/images/', 'images');
            f(images).finally(function() {
                if ($scope.callbacks.flatSeriesTable) {
                    $scope.flatSeriesSelected(null);     
                    $scope.callbacks.flatSeriesTable.reset();
                } 
                if ($scope.callbacks.seriesTable) {
                    $scope.seriesSelected(null);        
                    $scope.callbacks.seriesTable.reset();
                }
                updateSeriesTagsPromise();
            });
        });
    }

    function confirmAnonymizePatients(patients) {
        openConfirmActionModal('Anonymize', 'Force anonymization of ' + patients.length + ' patient(s)? Patient information will be lost.', 'Ok', function() {
            var imageIdToPatientPromise = createImageIdToPatientPromiseForPatients(patients);

            return anonymizeImages(imageIdToPatientPromise);
        });
    }

    function confirmAnonymizeStudies(studies) {
        openConfirmActionModal('Anonymize', 'Force anonymization of ' + studies.length + ' study(s)? Patient information will be lost.', 'Ok', function() {
            var imageIdToPatientPromise = createImageIdToPatientPromiseForStudies(studies);

            return anonymizeImages(imageIdToPatientPromise);
        });
    } 

    function confirmAnonymizeSeries(series) {
        // check if flat series
        series = series.map(function(s) {
            return s.series ? s.series : s;
        });

        openConfirmActionModal('Anonymize', 'Force anonymization of ' + series.length + ' series? Patient information will be lost.', 'Ok', function() {
            var imageIdToPatientPromise = createImageIdToPatientPromiseForSeries(series);

            return anonymizeImages(imageIdToPatientPromise);
        });
    }

    function anonymizeImages(imageIdToPatientPromise) {
        return showBoxSendTagValuesModal(imageIdToPatientPromise, function(imageTagValuesSeq) {
            var promises = imageTagValuesSeq.map(function(imageTagValues) {
                return $http.post('/api/images/' + imageTagValues.imageId + '/anonymize', imageTagValues.tagValues);
            });
            var allPromise = $q.all(promises);

            allPromise.finally(function () {
                $scope.patientSelected(null);        
                $scope.callbacks.patientsTable.reset();
                if ($scope.callbacks.flatSeriesTable) {
                    $scope.callbacks.flatSeriesTable.reset();
                }                
                updateSeriesTagsPromise();
            });

            return allPromise;
        }, "anonymized", "anonymize");
    }

    function createImageIdToPatientPromiseForPatients(patients) {
        var imageIdAndPatientsPromises = patients.map(function (patient) {
            return imagesForPatients([ patient ]).then(function (images) {
                return images.map(function (image) {
                    return { imageId: image.id, patient: patient };
                });
            });
        });

        return sbxMisc.flattenPromises(imageIdAndPatientsPromises).then(function(imageIdAndPatients) {
            return imageIdAndPatients.reduce(function ( imageIdToPatient, imageIdAndPatient ) {
                imageIdToPatient[ imageIdAndPatient.imageId ] = imageIdAndPatient.patient;
                return imageIdToPatient;
            }, {});                
        });        
    }

    function createImageIdToPatientPromiseForStudies(studies) {
        return $http.get('/api/metadata/patients/' + studies[0].patientId).then(function (patient) {
            return imagesForStudies(studies).then(function (images) {
                return images.reduce(function ( imageIdToPatient, image ) {
                    imageIdToPatient[ image.id ] = patient.data;
                    return imageIdToPatient;
                }, {});                
            });
        });
    }

    function createImageIdToPatientPromiseForSeries(series) {
        return $http.get('/api/metadata/studies/' + series[0].studyId).then(function (study) {
            return $http.get('/api/metadata/patients/' + study.data.patientId).then(function (patient) {
                return imagesForSeries(series).then(function (images) {
                    return images.reduce(function ( imageIdToPatient, image ) {
                        imageIdToPatient[ image.id ] = patient.data;
                        return imageIdToPatient;
                    }, {});                
                });
            });
        });
    }

    function tagSeriesForPatients(patients) {
        var seriesIdsPromise = seriesForPatients(patients).then(function (series) { return series.map(function (s) { return s.id; }); });
        openTagSeriesModal(seriesIdsPromise).then(function() { return updateSeriesTagsPromise(); });
    }

    function tagSeriesForStudies(studies) {
        var seriesIdsPromise = seriesForStudies(studies).then(function (series) { return series.map(function (s) { return s.id; }); });
        openTagSeriesModal(seriesIdsPromise).then(function() { return updateSeriesTagsPromise(); });
    }

    function tagSeries(series) {
        var seriesIds = series.map(function (s) { return s.id; });
        openTagSeriesModal($q.when(seriesIds)).then(function() { return updateSeriesTagsPromise(); });
    }

    function showBoxSendTagValuesModal(imageIdToPatientPromise, actionCallback, actionStringPastTense, actionString) {
        return $mdDialog.show({
                templateUrl: '/assets/partials/tagValuesModalContent.html',
                controller: 'TagValuesCtrl',
                scope: $scope.$new(),
                locals: {
                    imageIdToPatient: imageIdToPatientPromise,
                    actionCallback: actionCallback,
                    actionStringPastTense: actionStringPastTense,
                    actionString: actionString
                }
        });                
    }

    function confirmExportPatients(patients) {
        openConfirmActionModal('Export', 'This will export ' + patients.length + ' patient(s) as a zip archive. Proceed?', 'Ok', function() {
            var images = imagesForPatients(patients);
            return exportImages(images);
        });
    }

    function confirmExportStudies(studies) {
        openConfirmActionModal('Export', 'This will export ' + studies.length + ' study(s) as a zip archive. Proceed?', 'Ok', function() {
            var images = imagesForStudies(studies);
            return exportImages(images);
        });
    } 

    function confirmExportSeries(series) {
        openConfirmActionModal('Export', 'This will export ' + series.length + ' series as a zip archive. Proceed?', 'Ok', function() {
            var images = imagesForSeries(series);
            return exportImages(images);
        });
    }

    function exportImages(imagesPromise) {
        return imagesPromise.then(function (images) {
            var imageIds = images.map(function (image) {
                return image.id;
            });
            return $http.post('/api/users/generateauthtokens?n=1').success(function(tokens) {
                return $http.post('/api/images/export', imageIds).success(function (fileName) {
                    location.href = '/api/images/export?authtoken=' + tokens[0].token + '&filename=' + fileName.value;
                });
            });
        });
    }

    function addSeriesTypeRule(tagValues) {
        var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/addSeriesTypeRuleFromTagValuesModalContent.html',
            controller: 'AddSeriesTypeRuleFromTagValuesModalCtrl',
            locals: {
                    tagValues: tagValues
                },
            scope: $scope.$new()
        });

        return dialogPromise;
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

.controller('TagValuesCtrl', function($scope, $mdDialog, $http, sbxToast, imageIdToPatient, actionCallback, actionStringPastTense, actionString) {
    // Initialization
    $scope.title = 'Anonymization Options';

    // get unique patients and an their respective indices
    $scope.patients = [];
    var imageIds = [];
    for (var imageId in imageIdToPatient) {
        imageIds.push(parseInt(imageId));
        if (imageIdToPatient.hasOwnProperty(imageId)) {
            var patient = imageIdToPatient[imageId];
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

    $scope.actionButtonClicked = function() {
        var imageTagValuesSeq = [];
        for (var i = 0; i < imageIds.length; i++) {
            var imageId = imageIds[i];
            var patient = imageIdToPatient[imageId];
            var patientIndex = $scope.patients.indexOf(patient);
            var anonName = $scope.anonymizedPatientNames[patientIndex];
            imageTagValuesSeq.push( { imageId: imageId, tagValues: [ { tag: 0x00100010, value: anonName } ] } );
        }

        var actionPromise = actionCallback(imageTagValuesSeq);

        actionPromise.then(function(data) {
            $mdDialog.hide();
            sbxToast.showInfoMessage(imageIds.length + " images " + actionStringPastTense);
        }, function(data) {
            sbxToast.showErrorMessage('Failed to ' + actionString + ' images: ' + data);
        });

        return actionPromise;
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
    
})

.controller('AddSeriesTypeRuleFromTagValuesModalCtrl', function($scope, $mdDialog, $http, $q, tagValues) {
    // Initialization
    $scope.tagValues = tagValues;

    // Scope functions
    $scope.loadSeriesTypes = function() {
        var loadSeriesTypesPromise = $http.get('/api/seriestypes');

        $scope.seriesTypes = [];

        loadSeriesTypesPromise.success(function(seriesTypes) {
            $scope.seriesTypes = seriesTypes;
        });      

        return loadSeriesTypesPromise;
    };

    $scope.createRuleButtonClicked = function() {
        var savePromise = $http.post('/api/seriestypes/rules',
            { id: -1, seriesTypeId: $scope.seriesType.id });

        savePromise = savePromise.then(function(response) {
                return saveRuleAttributes(response.data);
            });

        savePromise.then(function() {
            sbxToast.showInfoMessage("Rule created");
            $mdDialog.hide();
        }, function(error) {
            sbxToast.showErrorMessage('Failed to create rule: ' + error);
        });

        return savePromise;
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

    // Private functions
    function saveRuleAttributes(rule) {
        var saveAttributePromises = [];
        var savePromise;

        angular.forEach($scope.tagValues, function(attribute) {
            newAttribute = {
                    id: -1,
                    seriesTypeRuleId: rule.id,
                    tag: attribute.tag,
                    name: attribute.name,
                    tagPath: attribute.tagPath.join(","),
                    namePath: attribute.namePath.join("/"),
                    values: attribute.values.join(",")
                };

            if (attribute.path && attribute.path.length > 0) {
                newAttribute.path = attribute.path;
            }

            savePromise = $http.post('/api/seriestypes/rules/' + rule.id + '/attributes', newAttribute);

            saveAttributePromises.push(savePromise);
        });

        return $q.all(saveAttributePromises);
    }
})

.controller('AdvancedFilteringModalCtrl', function($scope, $mdDialog, $http, sources, seriesTypes, seriesTags) {
    $scope.uiState = {
        sources: sources,
        seriesTypes: seriesTypes,
        seriesTags: seriesTags
    };

    $scope.applyButtonClicked = function() {
        var selectedSources = $scope.uiState.sources.filter(function(source) { return source.selected; });
        var selectedSeriesTypes = $scope.uiState.seriesTypes.filter(function(seriesType) { return seriesType.selected; });
        var selectedSeriesTags = $scope.uiState.seriesTags.filter(function(seriesTag) { return seriesTag.selected; });
        $mdDialog.hide({ selectedSources: selectedSources, selectedSeriesTypes: selectedSeriesTypes, selectedSeriesTags: selectedSeriesTags });
    };

    $scope.clearButtonClicked = function() {
        $scope.uiState.sources.map(function(source) { source.selected = false; });
        $scope.uiState.seriesTypes.map(function(seriesType) { seriesType.selected = false; });
        $scope.uiState.seriesTags.map(function(seriesTag) { seriesTag.selected = false; });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
})

.controller('ImageSettingsModalCtrl', function($scope, $mdDialog, $http, imageHeight, images, isWindowManual, windowMin, windowMax) {
    $scope.uiState.imageHeight = imageHeight;
    $scope.uiState.images = images;
    $scope.uiState.isWindowManual = isWindowManual;
    $scope.uiState.windowMin = windowMin;
    $scope.uiState.windowMax = windowMax;

    $scope.applyButtonClicked = function() {
        $mdDialog.hide($scope.uiState);
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});