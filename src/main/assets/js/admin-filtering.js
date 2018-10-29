(function () {
    'use strict';
}());

angular.module('slicebox.adminFiltering', ['ngRoute'])

    .config(function($routeProvider) {
        $routeProvider.when('/admin/filtering', {
            templateUrl: '/assets/partials/adminFiltering.html',
            controller: 'AdminFilteringCtrl'
        });
    })

    .controller('AdminFilteringCtrl', function($scope, $http, $mdDialog, $q, openAddEntityModal, openDeleteEntitiesModalFunction) {
        // Initialization
        $scope.uiState = {
            selectedFilter: null
        };

        $scope.associationObjectActions =
            [
                {
                    name: 'Delete',
                    action: openDeleteEntitiesModalFunction('/api/filtering/associations/', 'filter(s)')
                }
            ];

        $scope.filterObjectActions =
            [
                {
                    name: 'Delete',
                    action: confirmDeleteFilter
                }
            ];

        $scope.callbacks = {
            associationsTable: {},
            filtersTable: {},
            filterTagPathTables: {}
        };

        // Scope functions
        $scope.loadSourceFilterAssociations = function(startIndex, count) {
            var promises = {};
            promises.associations = ($http.get('/api/filtering/associations?startindex=' + startIndex + '&count=' + count));
            promises.sources = ($http.get("/api/sources"));
            promises.filters = ($http.get("/api/filtering/filters?count=100000"));
            return $q.all(promises).then(function(promisesResult) {
                return promisesResult.associations.data.map(function(association) {
                    var source = promisesResult.sources.data.find(function(s) {
                        return (s.sourceId === association.sourceId && s.sourceType === association.sourceType);
                    });
                    var filter = promisesResult.filters.data.find(function(f) {return f.id === association.tagFilterId;});
                    association.sourceName = source.sourceName;
                    association.filterName = filter.name;
                    association.filterType = filter.tagFilterType;
                    return association;
                });
            });
        };

        $scope.loadFilters = function(startIndex, count) {
            return $http.get('/api/filtering/filters?startindex=' + startIndex + '&count=' + count);
        };

        $scope.addAssociationButtonClicked = function() {
            openAddEntityModal('addSourceFilterAssociationModalContent.html', 'AddAssociationModalCtrl', '/api/filtering/associations', 'Source Filter Association', $scope.callbacks.associationsTable);
        };

        $scope.addFilterButtonClicked = function() {
            openAddEntityModal('addFilterModalContent.html', 'AddFilterModalCtrl', '/api/filtering/filters', 'Tag Filter', $scope.callbacks.filtersTable);
        };

        $scope.filterSelected = function(filter) {
            $scope.uiState.selectedFilter = filter;
        };

        function confirmDeleteFilter(filters) {
            var f = openDeleteEntitiesModalFunction('/api/filtering/filters/', 'filter(s)');
            f(filters).finally(function() {
                if ($scope.uiState.selectedFilter &&
                    filters.map(function(x) {return x.id;}).includes($scope.uiState.selectedFilter.id)) {
                    $scope.uiState.selectedFilter = null;
                }
                $scope.callbacks.filtersTable.clearSelection();
                $scope.callbacks.filtersTable.reloadPage();
                $scope.callbacks.filterTagPathTables.reloadPage();
                $scope.callbacks.associationsTable.reloadPage();
            });
        }
    })

    .controller('AddAssociationModalCtrl', function($scope, $mdDialog, $http) {

        $scope.uiState = {
            sources: [],
            filters: [],
            source: null,
            filter: null
        };

        // Scope functions

        $scope.loadSources = function() {
            return $http.get("/api/sources").success(function (sources) {
                $scope.uiState.sources = sources;
            });
        };

        $scope.loadFilters = function() {
            return $http.get("/api/filtering/filters?startindex=0&count=1000000").success(function (filters) {
                $scope.uiState.filters = filters;
            });
        };

        $scope.addButtonClicked = function() {
            $mdDialog.hide({
                id: -1,
                sourceType: $scope.uiState.source.sourceType,
                sourceId: $scope.uiState.source.sourceId,
                tagFilterId: $scope.uiState.filter.id
            });
        };

        $scope.cancelButtonClicked = function() {
            $mdDialog.cancel();
        };

    })

    .controller('FilterDetailsCtrl', function($scope, $http, $mdDialog, $q, sbxToast) {
        // Initialization
        $scope.state = {
            filterSpec: null
        };

        $scope.tagPathActions = [
            {
                name: 'Remove',
                action: removeTagPaths
            }
        ];

        $scope.$watch('uiState.selectedFilter', function() {
            resetState();
        });

        $scope.addFilterTagPathButtonClicked = function() {
            var dialogPromise = $mdDialog.show({
                templateUrl: '/assets/partials/editFilterTagPathModalContent.html',
                controller: 'EditFilterTagPathModalCtrl',
                locals: {
                    tagPath: {}
                }
            });

            dialogPromise.then(function (tagPath) {
                $scope.state.filterSpec.tagPaths.push(tagPath);
                saveFilter($scope.state.filterSpec).then(function(filterSpecData) {
                    $scope.state.filterSpec = filterSpecData.data;
                    $scope.callbacks.filterTagPathTables.reloadPage();
                });
            });
        };


        $scope.loadFilterTagPaths = function(startIndex, count) {
            if ($scope.state.filterSpec)
                return ($scope.state.filterSpec.tagPaths.slice(startIndex, startIndex + count) || []);
            return [];
        };

        $scope.saveButtonClicked = function () {
            var savePromise;
            var isCreate;

            if ($scope.uiState.selectedFilter.id === -1) {
                isCreate = true;
                savePromise = $http.post('/api/filtering/filters', $scope.state.filterSpec);
            } else {
                isCreate = false;
                savePromise = $http.post('/api/filtering/filters', $scope.state.filterSpec);
            }

            savePromise.then(function(response) {
                if (response.data.id) {
                    $scope.uiState.selectedFilter.id = response.data.id;

                }
                if (isCreate) {
                    sbxToast.showInfoMessage("Filter added");
                } else {
                    sbxToast.showInfoMessage("Filter updated");
                }

                resetState();
                $scope.callbacks.filterTagPathTables.reloadPage();

            }, function(error) {
                sbxToast.showErrorMessage(error);
            });

            return savePromise;
        };

        // Private functions
        function resetState() {
            $scope.originalFilterSpec = angular.copy($scope.uiState.selectedSeriesType);
            $scope.state.filterSpec = null;

            loadFilter();
        }

        function saveFilter(filter) {
            return $http.post('/api/filtering/filters', filter);
        }

        function loadFilter() {
            if (!$scope.uiState.selectedFilter ||
                $scope.uiState.selectedFilter.id === -1) {
                return;
            }

            $http.get('/api/filtering/filters/' + $scope.uiState.selectedFilter.id)
                .success(function(filter) {
                    $scope.state.filterSpec = filter;
                    $scope.state.originalFilterSpec = angular.copy(filter);
                    $scope.callbacks.filterTagPathTables.reloadPage();
                })
                .error(function(error) {
                    sbxToast.showErrorMessage('Failed to load filter: ' + error);
                });
        }

        function removeTagPaths(tagPaths) {
            var tagPathIndex;

            angular.forEach(tagPaths, function(tagPath) {

                tagPathIndex = $scope.state.filterSpec.tagPaths.indexOf(tagPath);
                if (tagPathIndex >= 0) {
                    $scope.state.filterSpec.tagPaths.splice(tagPathIndex, 1);
                }
            });
            saveFilter($scope.state.filterSpec).then(function() {
                $scope.callbacks.filterTagPathTables.clearSelection();
            });
        }
    })

    .controller('EditFilterTagPathModalCtrl', function($scope, $mdDialog, tagPath) {
        // Initialization
        $scope.tagPath = tagPath;

        // Scope functions
        $scope.cancelButtonClicked = function() {
            $mdDialog.cancel();
        };

        $scope.saveTagPath = function() {
            if ($scope.editTagPathForm.$invalid) {
                return;
            }

            $mdDialog.hide($scope.tagPath);
        };
    })

    .controller('AddFilterModalCtrl', function($scope, $mdDialog) {
    // Initialization
    $scope.filterType = 'WHITELIST';

    // Scope functions
    $scope.addButtonClicked = function() {
        var filterObject = { id: -1, name: $scope.filterName, tagFilterType: $scope.filterType, tags: []};
        if (filterObject.tagFilterType === "WHITELIST") {
            filterObject.tagPaths = [
                {tag: 'FileMetaInformationGroupLength'},
                {tag: 'FileMetaInformationVersion'},
                {tag: 'MediaStorageSOPClassUID'},
                {tag: 'MediaStorageSOPInstanceUID'},
                {tag: 'TransferSyntaxUID'},
                {tag: 'ImplementationClassUID'},
                {tag: 'ImageType'},
                {tag: 'SOPClassUID'},
                {tag: 'SOPInstanceUID'},
                {tag: 'StudyDate'},
                {tag: 'SeriesDate'},
                {tag: 'AccessionNumber'},
                {tag: 'Modality'},
                {tag: 'StudyDescription'},
                {tag: 'SeriesDescription'},
                {tag: 'PatientName'},
                {tag: 'PatientID'},
                {tag: 'PatientBirthDate'},
                {tag: 'PatientSex'},
                {tag: 'PatientAge'},
                {tag: 'ProtocolName'},
                {tag: 'StudyInstanceUID'},
                {tag: 'SeriesInstanceUID'},
                {tag: 'StudyID'},
                {tag: 'SamplesPerPixel'},
                {tag: 'PhotometricInterpretation'},
                {tag: 'Rows'},
                {tag: 'Columns'},
                {tag: 'BitsAllocated'},
                {tag: 'BitsStored'},
                {tag: 'HighBit'},
                {tag: 'PixelRepresentation'},
                {tag: 'PixelData'}
                ];
        }
        $mdDialog.hide(filterObject);
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});