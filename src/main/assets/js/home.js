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

.controller('HomeCtrl', function($timeout, $scope, $http, $mdDialog, $q, openMessageModal, openConfirmActionModal, openBulkDeleteEntitiesModalFunction, openTagSeriesModal, sbxMisc, sbxMetaData, sbxToast) {

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
                name: 'Tag Series',
                action: tagSeriesForPatients
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizePatients
            },
            {
                name: 'Export',
                action: confirmExportPatients
            },
            {
                name: "Summary",
                action: summaryForPatients
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
                name: 'Tag Series',
                action: tagSeriesForStudies
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizeStudies
            },
            {
                name: 'Export',
                action: confirmExportStudies
            },
            {
                name: "Summary",
                action: summaryForStudies
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
                name: 'Tag Series',
                action: tagSeries
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizeSeries
            },
            {
                name: 'Export',
                action: confirmExportSeries
            },
            {
                name: "Summary",
                action: summaryForSeries
            }
        ];

    $scope.flatSeriesActions =
        [
            {
                name: 'Send',
                action: confirmSendFlatSeries
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
                name: 'Tag Series',
                action: tagSeries
            },
            {
                name: 'Anonymize',
                action: confirmAnonymizeFlatSeries
            },
            {
                name: 'Export',
                action: confirmExportSeries
            },
            {
                name: "Summary",
                action: summaryForSeries
            }
        ];

    $scope.imageActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteImages
            },
            {
                name: 'Export',
                action: confirmExportImages
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

    if (!$scope.uiState.patientTableState) {
        $scope.uiState.patientTableState = {};
        $scope.uiState.studyTableState = {};
        $scope.uiState.seriesTableState = {};
        $scope.uiState.flatTableState = {};
        $scope.uiState.imageTableState = {};
        $scope.uiState.attributesTableState = {};

        $scope.uiState.selectedPatient = null;
        $scope.uiState.selectedStudy = null;
        $scope.uiState.selectedSeries = null;
        $scope.uiState.selectedImage = null;
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
            },
            typeState: {
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
    }

    $scope.uiState.advancedFiltering.sourcesPromise = $http.get('/api/sources').then(function (sourcesData) {
        if (sourcesData.status === 200) {
            return sourcesData.data.map(function (source) {
                source.selected = false;
                return source;
            });
        } else {
            return [];
        }
    }, function () { return []; });
    $scope.uiState.advancedFiltering.seriesTypesPromise = $http.get('/api/seriestypes').then(function (seriesTypesData) {
        if (seriesTypesData.status === 200) {
            return seriesTypesData.data.map(function (seriesType) {
                seriesType.selected = false;
                return seriesType;
            });
        } else {
            return [];
        }
    }, function () { return []; });
    updateSeriesTagsPromise();

    // Scope functions

    $scope.findSeriesTypes = function(searchText) {
        var lcSearchText = angular.lowercase(searchText);
        var selectedTypeNames = $scope.uiState.seriesDetails.selectedSeriesSeriesTypes.map(function (seriesType) { return seriesType.name; });
        return searchText ? $scope.uiState.advancedFiltering.seriesTypesPromise.then(function (seriesTypes) {
            return seriesTypes.filter(function (seriesType) {
                var lcName = angular.lowercase(seriesType.name);
                return lcName.indexOf(lcSearchText) === 0;
            }).filter(function (seriesType) {
                return selectedTypeNames.indexOf(seriesType.name) < 0;
            });
        }) : [];
    };

    $scope.seriesTypeAdded = function(seriesType) {
        $http.put('/api/metadata/series/' + $scope.uiState.selectedSeries.id + '/seriestypes/' + seriesType.id);
        return seriesType;
    };

    $scope.seriesTypeRemoved = function(seriesType) {
        $http.delete('/api/metadata/series/' + $scope.uiState.selectedSeries.id + '/seriestypes/' + seriesType.id);
    };

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
        $http.post('/api/metadata/series/' + $scope.uiState.selectedSeries.id + '/seriestags', theTag).then(function (addedTag) {
            // copy database id to selected tag
            $scope.uiState.seriesDetails.selectedSeriesSeriesTags.forEach(function (selectedTag) {
                if (selectedTag.name === addedTag.data.name) {
                    selectedTag.id = addedTag.data.id;
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
        $http.delete('/api/metadata/series/' + $scope.uiState.selectedSeries.id + '/seriestags/' + tag.id).then(function () {
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
                var orderByPropertyName = orderByProperty === "id" ? orderByProperty : orderByProperty.substring(0, orderByProperty.indexOf('['));
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
        $scope.uiState.flatTableState = {};
        $scope.uiState.studyTableState = {};
        $scope.uiState.seriesTableState = {};
        $scope.uiState.attributesTableState = {};
        $scope.uiState.selectedPatient = patient;
        $scope.studySelected(null, true);
        if ($scope.callbacks.patientsTable) {
            $scope.callbacks.patientsTable.reloadPage();
        }
    };

    $scope.loadStudies = function(startIndex, count) {
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
        $scope.uiState.seriesTableState = {};
        $scope.uiState.attributesTableState = {};
        $scope.uiState.selectedStudy = study;
        $scope.seriesSelected(null, true);
        if (reset && $scope.callbacks.studiesTable) {
            $scope.callbacks.studiesTable.reset();
        } else if ($scope.callbacks.studiesTable) {
            $scope.callbacks.studiesTable.reloadPage();
        }
    };

    $scope.loadSeries = function(startIndex, count) {
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
            var orderByPropertyName = orderByProperty === "id" ? orderByProperty : orderByProperty.substring(orderByProperty.indexOf('.') + 1, orderByProperty.indexOf('['));
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

    $scope.loadImages = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        if ($scope.uiState.selectedSeries === null) {
            return [];
        }

        var loadImagesUrl = '/api/metadata/images?startindex=' + startIndex + '&count=' + count + '&seriesid=' + $scope.uiState.selectedSeries.id;

        if (orderByProperty) {
            var orderByPropertyName = orderByProperty === "id" ? orderByProperty : orderByProperty.substring(0, orderByProperty.indexOf('['));
            loadImagesUrl = loadImagesUrl + '&orderby=' + orderByPropertyName;

            if (orderByDirection === 'ASCENDING') {
                loadImagesUrl = loadImagesUrl + '&orderascending=true';
            } else {
                loadImagesUrl = loadImagesUrl + '&orderascending=false';
            }
        }

        if (filter) {
            loadImagesUrl = loadImagesUrl + '&filter=' + encodeURIComponent(filter);
        }

        var loadImagesPromise = $http.get(loadImagesUrl);

        loadImagesPromise.error(function(error) {
            sbxToast.showErrorMessage('Failed to load images: ' + error);
        });

        return loadImagesPromise;
    };

    $scope.seriesSelected = function(series, reset) {
        $scope.uiState.selectedSeries = series;

        $scope.uiState.seriesDetails.selectedSeriesSource = "";
        $scope.uiState.seriesDetails.selectedSeriesSeriesTypes = [];
        $scope.uiState.seriesDetails.selectedSeriesSeriesTags = [];
        $scope.uiState.seriesDetails.pngImageUrls = [];
        $scope.uiState.seriesDetails.tagState.searchText = "";

        $scope.updatePNGImageUrls();

        if (series !== null) {
            updateSelectedSeriesSource(series);
            updateSelectedSeriesSeriesTypes(series);
            updateSelectedSeriesSeriesTags(series);
        }

        if ($scope.callbacks.imageTable) {
            $scope.callbacks.imageTable.reset();
        }
        if ($scope.callbacks.imageAttributesTable) {
            $scope.callbacks.imageAttributesTable.reset();
        }
        if (reset && $scope.callbacks.seriesTable) {
            $scope.callbacks.seriesTable.reset();
        }
    };

    $scope.flatSeriesSelected = function(flatSeries) {

        if (flatSeries !== null) {
            $scope.uiState.selectedPatient = null;
            $scope.uiState.selectedStudy = null;
            $scope.uiState.patientTableState = {};
            $scope.uiState.studyTableState = {};
            $scope.uiState.seriesTableState = {};
            $scope.seriesSelected(flatSeries.series);
        } else {
            $scope.seriesSelected(null);
        }
    };

    $scope.imageSelected = function(image) {
        $scope.uiState.selectedImage = image;

        if ($scope.callbacks.imageAttributesTable) {
            $scope.callbacks.imageAttributesTable.reset();
        }
    };

    $scope.loadImageAttributes = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var imagesPromise = $scope.uiState.selectedImage ? $q.when([$scope.uiState.selectedImage]) :
            $http.get('/api/metadata/images?count=1&seriesid=' + $scope.uiState.selectedSeries.id).then(function (images) {
                return images.data;
            });

        return imagesPromise.then(function(images) {
            if (images.length > 0) {
                return $http.get('/api/images/' + images[0].id + '/attributes').then(function(attributes) {
                    if (filter) {
                        var filterLc = filter.toLowerCase();
                        attributes.data = attributes.data.filter(function (attribute) {
                            var nameCondition = attribute.name.toLowerCase().indexOf(filterLc) >= 0;
                            var tagCondition = toHexString(attribute.tag).indexOf(filterLc) >= 0;
                            var valuesCondition = attribute.values.reduce(function (c, value) {
                                return c | value.toLowerCase().indexOf(filterLc) >= 0;
                            }, false);
                            return nameCondition || tagCondition || valuesCondition;
                        });
                    }
                    if (orderByProperty) {
                        if (!orderByDirection) {
                            orderByDirection = 'ASCENDING';
                        }
                        return attributes.data.sort(function compare(a,b) {
                          return orderByDirection === 'ASCENDING' ?
                            a[orderByProperty] < b[orderByProperty] ? -1 : a[orderByProperty] > b[orderByProperty] ? 1 : 0 :
                            a[orderByProperty] > b[orderByProperty] ? -1 : a[orderByProperty] < b[orderByProperty] ? 1 : 0;
                        });
                    } else {
                        return attributes.data;
                    }
                }, function(error) {
                    sbxToast.showErrorMessage('Failed to load image attributes: ' + error);
                });
            } else {
                return [];
            }
        }, function(error) {
            sbxToast.showErrorMessage('Failed to load images for series: ' + error);
        });
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
        if ($scope.uiState.selectedSeries !== null && !$scope.uiState.loadPngImagesInProgress) {
            $scope.uiState.seriesDetails.pngImageUrls = [];
            $scope.uiState.loadPngImagesInProgress = true;

            var imagesPromise = $scope.uiState.selectedImage ? $q.when([$scope.uiState.selectedImage]) :
                $http.get('/api/metadata/images?count=' + $scope.uiState.seriesDetails.images + '&seriesid=' + $scope.uiState.selectedSeries.id).then(function(images) {
                    return images.data;
                });

            imagesPromise.then(function (images) {
                var generateMore = true;

                angular.forEach(images, function(image, imageIndex) {

                    if (imageIndex < $scope.uiState.seriesDetails.images) {

                        $http.get('/api/images/' + image.id + '/imageinformation').then(function(info) {
                            if (!$scope.uiState.seriesDetails.isWindowManual) {
                                $scope.uiState.seriesDetails.windowMin = info.data.minimumPixelValue;
                                $scope.uiState.seriesDetails.windowMax = info.data.maximumPixelValue;
                            }
                            for (var j = 0; j < info.data.numberOfFrames && generateMore; j++) {

                                var url = '/api/images/' + image.id + '/png' + '?framenumber=' + (j + 1);
                                if ($scope.uiState.seriesDetails.isWindowManual) {
                                    url = url +
                                        '&windowmin=' + $scope.uiState.seriesDetails.windowMin +
                                        '&windowmax=' + $scope.uiState.seriesDetails.windowMax;
                                }
                                if (!isNaN(parseInt($scope.uiState.seriesDetails.imageHeight))) {
                                    url = url +
                                        '&imageheight=' + $scope.uiState.seriesDetails.imageHeight;
                                }
                                var frameIndex = Math.max(0, info.data.frameIndex - 1)*Math.max(1, info.data.numberOfFrames) + (j + 1);
                                $scope.uiState.seriesDetails.pngImageUrls.push({ url: url, frameIndex: frameIndex });
                                generateMore = $scope.uiState.seriesDetails.pngImageUrls.length < $scope.uiState.seriesDetails.images &&
                                                !(imageIndex === images.length - 1 && j === info.data.numberOfFrames - 1);
                            }
                            if (!generateMore) {
                                $scope.uiState.loadPngImagesInProgress = false;
                            }
                        }, function(error) {
                            sbxToast.showErrorMessage('Failed to load image information: ' + error);
                            $scope.uiState.loadPngImagesInProgress = false;
                        });

                    }

                });
            }, function(reason) {
                sbxToast.showErrorMessage('Failed to load images for series: ' + reason);
                $scope.uiState.loadPngImagesInProgress = false;
            });

        }
    };

    // Private functions

    function toHexString(intValue) {
        var returnValue = intValue.toString(16);
        while (returnValue.length < 8) {
            returnValue = '0' + returnValue;
        }
        return returnValue.toUpperCase();
    }

    function urlWithAdvancedFiltering(url) {
        return sbxMetaData.urlWithAdvancedFiltering(
            url,
            $scope.uiState.advancedFiltering.selectedSources,
            $scope.uiState.advancedFiltering.selectedSeriesTypes,
            $scope.uiState.advancedFiltering.selectedSeriesTags);
    }

    function studiesForPatients(patients) {
        return sbxMetaData.studiesForPatients(
            patients,
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

    function imagesForPatients(patients) {
        return sbxMetaData.imagesForPatients(
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

    function imagesForStudies(studies) {
        return sbxMetaData.imagesForStudies(
            studies,
            $scope.uiState.advancedFiltering.selectedSources,
            $scope.uiState.advancedFiltering.selectedSeriesTypes,
            $scope.uiState.advancedFiltering.selectedSeriesTags);
    }

    function imagesForSeries(series) {
        return sbxMetaData.imagesForSeries(series);
    }

    function updateSeriesTagsPromise() {
        $scope.uiState.advancedFiltering.seriesTagsPromise = $scope.uiState.advancedFiltering.seriesTagsPromise.then(function (oldTags) {
            return $http.get('/api/metadata/seriestags?count=100000').then(function (newTagsData) {
                if (newTagsData.status === 200) {
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
                } else {
                    return [];
                }
            }, function () { return []; });
        });
    }

    function updateSelectedSeriesSource(series) {
        return $http.get('/api/metadata/series/' + series.id + '/source').then(function (source) {
            $scope.uiState.seriesDetails.selectedSeriesSource = source.data.sourceName + " (" + source.data.sourceType + ")";
        });
    }

    function updateSelectedSeriesSeriesTypes(series) {
        return $http.get('/api/metadata/series/' + series.id + '/seriestypes').then(function (seriesTypes) {
            $scope.uiState.seriesDetails.selectedSeriesSeriesTypes = seriesTypes.data;
        });
    }

    function updateSelectedSeriesSeriesTags(series) {
        return $http.get('/api/metadata/series/' + series.id + '/seriestags').then(function (seriesTags) {
            $scope.uiState.seriesDetails.selectedSeriesSeriesTags = seriesTags.data;
        });
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
            var imageIdsAndPatientsPromise = createImageIdsAndPatientsPromiseForPatients(patients);

            return showBoxSendTagValuesModal(imageIdsAndPatientsPromise, function(imageTagValuesSeq) {
                return $http.post('/api/boxes/' + receiverId + '/send', imageTagValuesSeq);
            }, "sent", "send");
        });
    }

    function confirmSendStudies(studies) {
        return confirmSend('/api/boxes', function(receiverId) {
            var imageIdsAndPatientsPromise = createImageIdsAndPatientsPromiseForStudies(studies);

            return showBoxSendTagValuesModal(imageIdsAndPatientsPromise, function (imageTagValuesSeq) {
                return $http.post('/api/boxes/' + receiverId + '/send', imageTagValuesSeq);
            }, "sent", "send");
        });
    }

    function confirmSendSeries(series) {
        return confirmSend('/api/boxes', function(receiverId) {
            var imageIdsAndPatientsPromise = createImageIdsAndPatientsPromiseForSeries(series);

            return showBoxSendTagValuesModal(imageIdsAndPatientsPromise, function(imageTagValuesSeq) {
                return $http.post('/api/boxes/' + receiverId + '/send', imageTagValuesSeq);
            }, "sent", "send");
        });
    }

    function confirmSendFlatSeries(flatSeries) {
        return confirmSend('/api/boxes', function(receiverId) {
            var imageIdsAndPatientsPromise = createImageIdsAndPatientsPromiseForFlatSeries(flatSeries);

            return showBoxSendTagValuesModal(imageIdsAndPatientsPromise, function(imageTagValuesSeq) {
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
            return $http.post('/api/scus/' + receiverId + '/send', imageIds).then(function() {
                $mdDialog.hide();
                sbxToast.showInfoMessage("Series sent to SCP");
            }, function(error) {
                sbxToast.showErrorMessage('Failed to send to SCP: ' + error);
            });
        });
    }

    function confirmDeletePatients(patients) {
        imagesForPatients(patients).then(function(images) {
            var imageIds = images.map(function (image) { return image.id; });
            var f = openBulkDeleteEntitiesModalFunction('/api/images/delete', 'images');
            f(imageIds).finally(function() {
                $scope.patientSelected(null);
                $scope.callbacks.patientsTable.reset();
                updateSeriesTagsPromise();
            });
        });
    }

    function confirmDeleteStudies(studies) {
        imagesForStudies(studies).then(function(images) {
            var imageIds = images.map(function (image) { return image.id; });
            var f = openBulkDeleteEntitiesModalFunction('/api/images/delete', 'images');
            f(imageIds).finally(function() {
                $scope.studySelected(null);
                $scope.callbacks.studiesTable.reset();
                updateSeriesTagsPromise();
            });
        });
    }

    function confirmDeleteSeries(series) {
        imagesForSeries(series).then(function(images) {
            var imageIds = images.map(function (image) { return image.id; });
            var f = openBulkDeleteEntitiesModalFunction('/api/images/delete', 'images');
            f(imageIds).finally(function() {
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

    function confirmDeleteImages(images) {
        var imageIds = images.map(function (image) { return image.id; });
        var f = openBulkDeleteEntitiesModalFunction('/api/images/delete', 'images');
        f(imageIds).finally(function() {
            $scope.imageSelected(null);
            $scope.callbacks.imageTable.reset();
            updateSeriesTagsPromise();
        });
    }

    function confirmAnonymizePatients(patients) {
        openConfirmActionModal('Anonymize', 'Force anonymization of ' + patients.length + ' patient(s)? Patient information will be lost.', 'Ok', function() {
            var imageIdsAndPatientsPromise = createImageIdsAndPatientsPromiseForPatients(patients);

            return anonymizeImages(imageIdsAndPatientsPromise);
        });
    }

    function confirmAnonymizeStudies(studies) {
        openConfirmActionModal('Anonymize', 'Force anonymization of ' + studies.length + ' study(s)? Patient information will be lost.', 'Ok', function() {
            var imageIdsAndPatientsPromise = createImageIdsAndPatientsPromiseForStudies(studies);

            return anonymizeImages(imageIdsAndPatientsPromise);
        });
    }

    function confirmAnonymizeSeries(series) {
        openConfirmActionModal('Anonymize', 'Force anonymization of ' + series.length + ' series? Patient information will be lost.', 'Ok', function() {
            var imageIdsAndPatientsPromise = createImageIdsAndPatientsPromiseForSeries(series);

            return anonymizeImages(imageIdsAndPatientsPromise);
        });
    }

    function confirmAnonymizeFlatSeries(flatSeries) {
        openConfirmActionModal('Anonymize', 'Force anonymization of ' + series.length + ' series? Patient information will be lost.', 'Ok', function() {
            var imageIdsAndPatientsPromise = createImageIdsAndPatientsPromiseForFlatSeries(flatSeries);

            return anonymizeImages(imageIdsAndPatientsPromise);
        });
    }

    function anonymizeImages(imageIdsAndPatientsPromise) {
        return showBoxSendTagValuesModal(imageIdsAndPatientsPromise, function(imageTagValuesSeq) {
            var promise = $http.post('/api/anonymization/anonymize', imageTagValuesSeq);

            promise.finally(function () {
                $scope.patientSelected(null);
                $scope.callbacks.patientsTable.reset();
                if ($scope.callbacks.flatSeriesTable) {
                    $scope.callbacks.flatSeriesTable.reset();
                }
                updateSeriesTagsPromise();
            });

            return promise;
        }, "anonymized", "anonymize");
    }

    function createImageIdsAndPatientsPromiseForPatients(patients) {
        var imageIdAndPatientsPromises = patients.map(function (patient) {
            return imagesForPatients([ patient ]).then(function (images) {
                return images.map(function (image) {
                    return { imageId: image.id, patient: patient };
                });
            });
        });

        return sbxMisc.flattenPromises(imageIdAndPatientsPromises);
    }

    function createImageIdsAndPatientsPromiseForStudies(studies) {
        return $http.get('/api/metadata/patients/' + studies[0].patientId).then(function (patient) {
            return imagesForStudies(studies).then(function (images) {
                return images.map(function (image) {
                    return { imageId: image.id, patient: patient.data };
                });
            });
        });
    }

    function createImageIdsAndPatientsPromiseForSeries(series) {
        return $http.get('/api/metadata/studies/' + series[0].studyId).then(function (study) {
            return $http.get('/api/metadata/patients/' + study.data.patientId).then(function (patient) {
                return imagesForSeries(series).then(function (images) {
                    return images.map(function (image) {
                        return { imageId: image.id, patient: patient.data };
                    });
                });
            });
        });
    }

    function createImageIdsAndPatientsPromiseForFlatSeries(flatSeries) {
        var imageIdAndPatientsPromises = flatSeries.map(function (fs) {
            return imagesForSeries([ fs.series ]).then(function (images) {
                return images.map(function (image) {
                    return { imageId: image.id, patient: fs.patient };
                });
            });
        });

        return sbxMisc.flattenPromises(imageIdAndPatientsPromises);
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

    function showBoxSendTagValuesModal(imageIdsAndPatientsPromise, actionCallback, actionStringPastTense, actionString) {
        return imageIdsAndPatientsPromise.then(function(imageIdsAndPatients) {
            return $mdDialog.show({
                templateUrl: '/assets/partials/tagValuesModalContent.html',
                controller: 'TagValuesCtrl',
                scope: $scope.$new(),
                locals: {
                    imageIdsAndPatients: imageIdsAndPatients,
                    actionCallback: actionCallback,
                    actionStringPastTense: actionStringPastTense,
                    actionString: actionString
                }
            });
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

    function confirmExportImages(images) {
        openConfirmActionModal('Export', 'This will export ' + images.length + ' images as a zip archive. Proceed?', 'Ok', function() {
            return exportImages($q.when(images));
        });
    }

    function exportImages(imagesPromise) {
        return imagesPromise.then(function (images) {
            var imageIds = images.map(function (image) {
                return image.id;
            });
            return $http.post('/api/images/export', imageIds).then(function (exportSetId) {
                location.href = '/api/images/export?id=' + exportSetId.data.id;
            });
        });
    }

    function summaryForPatients(patients) {
        studiesForPatients(patients).then(function (st) {
            seriesForStudies(st).then(function (se) {
                summaryStringForSeries(se).then(function (summaryString) {
                    var summary =
                        '<ul>' +
                            '<li>' + patients.length + ' patients</li>' +
                            '<li>' + st.length + ' studies</li>' + summaryString +
                        '</ul>';
                    openMessageModal('Patients Summary', summary);
                });
            });
        });
    }

    function summaryForStudies(studies) {
        seriesForStudies(studies).then(function (se) {
            summaryStringForSeries(se).then(function (summaryString) {
                var summary =
                    '<ul>' +
                        '<li>' + studies.length + ' studies</li>' + summaryString +
                    '</ul>';
                openMessageModal('Studies Summary', summary);
            });
        });
    }

    function summaryForSeries(series) {
        if (series.length > 0 && series[0].patient) { series = series.map(function (s) { return s.series; }); }
        summaryStringForSeries(series).then(function (summaryString) {
            var summary = '<ul>' + summaryString + '</ul>';
            openMessageModal('Series Summary', summary);
        });
    }

    function summaryStringForSeries(series) {
        return imagesForSeries(series).then(function (im) {

            var modalities = series.reduce(function (map, s) {
                if (map[s.modality.value]) {
                    map[s.modality.value] += 1;
                } else {
                    map[s.modality.value] = 1;
                }
                return map;
            }, {});
            var modalitiesString = JSON.stringify(modalities).replace(/\"/g,'');
            modalitiesString = modalitiesString.substring(1, modalitiesString.length - 1);

            return '<li>' + series.length + ' series</li>' +
                   '<li>' + im.length + ' images</li>' +
                   '<li>Modalities (# series): ' + modalitiesString + '</li>';
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
        return receiverSelectedCallback($scope.uiState.selectedReceiver.id).then(function(data) {
            $mdDialog.hide();
            return data;
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

})

.controller('TagValuesCtrl', function($scope, $mdDialog, $http, sbxToast, imageIdsAndPatients, actionCallback, actionStringPastTense, actionString) {
    // Initialization
    $scope.title = 'Anonymization Options';
    $scope.patients = [];
    var imageIds = [];

    // get unique patients and and their respective indices
    imageIdsAndPatients.forEach(function (imageIdAndPatient) {
        imageIds.push(imageIdAndPatient.imageId);

        var patient = imageIdAndPatient.patient;
        if ($scope.patients.filter(function (aPatient) { return aPatient.id === patient.id; }).length === 0) {
            $scope.patients.push(patient);
        }
    });

    var patientIds = $scope.patients.map(function (patient) { return patient.id; });

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

    $scope.keepButtonClicked = function() {
        $scope.anonymizedPatientNames = $scope.patients.map(function(patient) { return patient.patientName.value; });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

    $scope.actionButtonClicked = function() {
        var imageTagValuesSeq = [];
        for (var i = 0; i < imageIdsAndPatients.length; i++) {
            var imageId = imageIdsAndPatients[i].imageId;
            var patient = imageIdsAndPatients[i].patient;
            var patientIndex = patientIds.indexOf(patient.id);
            var anonName = $scope.anonymizedPatientNames[patientIndex];
            if (anonName && anonName.length > 0) {
                imageTagValuesSeq.push( { imageId: imageId, tagValues: [ { tag: 0x00100010, value: anonName } ] } );
            } else {
                imageTagValuesSeq.push( { imageId: imageId, tagValues: [ ] } );
            }
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

.controller('AddSeriesTypeRuleFromTagValuesModalCtrl', function($scope, $mdDialog, $http, $q, sbxToast, tagValues) {
    // Initialization
    $scope.tagValues = tagValues;

    // Scope functions
    $scope.loadSeriesTypes = function() {
        var loadSeriesTypesPromise = $http.get('/api/seriestypes');

        $scope.seriesTypes = [];

        loadSeriesTypesPromise.then(function(seriesTypes) {
            $scope.seriesTypes = seriesTypes.data;
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