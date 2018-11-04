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
        return $http.get('/api/filtering/associations?startindex=' + startIndex + '&count=' + count);
    };

    $scope.loadFilters = function(startIndex, count) {
        return $http.get('/api/filtering/filters?startindex=' + startIndex + '&count=' + count);
    };

    $scope.addAssociationButtonClicked = function() {
        openAddEntityModal('addSourceFilterAssociationModalContent.html', 'AddAssociationModalCtrl', '/api/filtering/associations', 'Source Filter Association', $scope.callbacks.associationsTable);
    };

    $scope.addFilterButtonClicked = function() {
        openAddEntityModal('addFilterModalContent.html', 'AddFilterModalCtrl', '/api/filtering/filters', 'Tag Filter', $scope.callbacks.filtersTable)
            .then(function (tagFilter) {
                if (tagFilter.tagFilterType === 'WHITELIST') {
                    var defaultTags = [
                        'FileMetaInformationGroupLength',
                        'FileMetaInformationVersion',
                        'MediaStorageSOPClassUID',
                        'MediaStorageSOPInstanceUID',
                        'TransferSyntaxUID',
                        'ImplementationClassUID',
                        'ImageType',
                        'SOPClassUID',
                        'SOPInstanceUID',
                        'StudyDate',
                        'SeriesDate',
                        'AccessionNumber',
                        'Modality',
                        'StudyDescription',
                        'SeriesDescription',
                        'PatientName',
                        'PatientID',
                        'PatientBirthDate',
                        'PatientSex',
                        'PatientAge',
                        'ProtocolName',
                        'StudyInstanceUID',
                        'SeriesInstanceUID',
                        'StudyID',
                        'SamplesPerPixel',
                        'PhotometricInterpretation',
                        'Rows',
                        'Columns',
                        'BitsAllocated',
                        'BitsStored',
                        'HighBit',
                        'PixelRepresentation',
                        'PixelData'];
                    return $q.all(defaultTags.map(function(tagName) {
                        return $scope.addTagPath(tagFilter.id, { name: tagName });
                    }));
                }
            });
    };

    $scope.filterSelected = function(filter) {
        $scope.uiState.selectedFilter = filter;
        if ($scope.callbacks.filterTagPathTables && $scope.callbacks.filterTagPathTables.reloadPage) {
            $scope.callbacks.filterTagPathTables.reloadPage();
        }
    };

    // this function is on scope to be available in sub controllers
    $scope.addTagPath = function(tagFilterId, tagPath) {
        var tagFilterTagPath = {
            id: -1,
            tagFilterId: tagFilterId,
            tagPath: tagPath
        };
        return $http.post('/api/filtering/filters/' + tagFilterId + '/tagpaths', tagFilterTagPath);
    };

    // Private functions
    function confirmDeleteFilter(filters) {
        var f = openDeleteEntitiesModalFunction('/api/filtering/filters/', 'filter(s)');
        f(filters).finally(function() {
            if ($scope.uiState.selectedFilter &&
                filters.map(function(x) {return x.id;}).includes($scope.uiState.selectedFilter.id)) {
                $scope.uiState.selectedFilter = null;
            }
            $scope.callbacks.filtersTable.reloadPage();
            $scope.callbacks.associationsTable.reloadPage();
            if ($scope.callbacks.filterTagPathTables && $scope.callbacks.filterTagPathTables.reloadPage) {
                $scope.callbacks.filterTagPathTables.reloadPage();
            }
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
        return $http.get("/api/sources").then(function (sources) {
            $scope.uiState.sources = sources.data;
        });
    };

    $scope.loadFilters = function() {
        return $http.get("/api/filtering/filters?startindex=0&count=1000000").then(function (filters) {
            $scope.uiState.filters = filters.data;
        });
    };

    $scope.addButtonClicked = function() {
        $mdDialog.hide({
            id: -1,
            sourceType: $scope.uiState.source.sourceType,
            sourceName: $scope.uiState.source.sourceName,
            sourceId: $scope.uiState.source.sourceId,
            tagFilterId: $scope.uiState.filter.id,
            tagFilterName: $scope.uiState.filter.name
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

})

.controller('FilterDetailsCtrl', function($scope, $http, $mdDialog, $q, sbxToast, sbxUtil) {
    // Initialization
    $scope.tagPathActions = [
        {
            name: 'Remove',
            action: removeTagPaths
        }
    ];

    // Scope functions
    $scope.loadFilterTagPaths = function(startIndex, count) {
        if (!$scope.uiState.selectedFilter) {
            return $q.when([]);
        } else {
            return $http.get('/api/filtering/filters/' + $scope.uiState.selectedFilter.id + '/tagpaths?startindex=' + startIndex + '&count=' + count)
                .then(function(tagPaths) {
                    return tagPaths.data.map(function (tagPath) {
                            return {
                                id: tagPath.id,
                                tags: sbxUtil.tagPathToString(tagPath.tagPath, 'tags'),
                                names: sbxUtil.tagPathToString(tagPath.tagPath, 'names')
                            };
                        });
                });
        }
    };

    $scope.addFilterTagPathButtonClicked = function() {
        if ($scope.uiState.selectedFilter) {
            var dialogPromise = $mdDialog.show({
                templateUrl: '/assets/partials/editFilterTagPathModalContent.html',
                controller: 'EditFilterTagPathModalCtrl',
                locals: {
                    tagPath: {}
                }
            });

            return dialogPromise.then(function (tagPath) {
                return $scope.addTagPath($scope.uiState.selectedFilter.id, tagPath).then(function() {
                    sbxToast.showInfoMessage("Tag path added");
                    $scope.callbacks.filterTagPathTables.reloadPage();
                });
            });
        }
    };

    // Private functions
    function removeTagPaths(tagPaths) {
        if (!$scope.uiState.selectedFilter.id || tagPaths.length === 0) {
            return $q.when();
        } else {
            return $q.all(tagPaths.map(function(tagPath) {
                return $http.delete('/api/filtering/filters/' + $scope.uiState.selectedFilter.id + '/tagpaths/' + tagPath.id);
            })).then(function() {
                sbxToast.showInfoMessage("Tag paths deleted");
            });
        }
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
        var filterObject = { id: -1, name: $scope.filterName, tagFilterType: $scope.filterType, tagPaths: []};
        $mdDialog.hide(filterObject);
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});
