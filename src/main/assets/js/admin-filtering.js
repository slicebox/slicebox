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
                    //action: openDeleteEntitiesModalFunction('/api/filtering/tagfilter/', 'filter(s)')
                }
            ];

        $scope.callbacks = {
            associationsTable: {},
            filtersTable: {},
            filterAttributesTables: {}
        };

        // Scope functions
        $scope.loadSourceFilterAssociations = function(startIndex, count, orderByProperty, orderByDirection) {
            var promises = {};
            promises.associations = ($http.get('/api/filtering/associations?startindex=' + startIndex + '&count=' + count));
            promises.sources = ($http.get("/api/sources"));
            promises.filters = ($http.get("/api/filtering/tagfilter?count=100000"));
            var combinedPromise = $q.all(promises).then(function(promisesResult) {
                var resultArray = promisesResult.associations.data.map(function(association) {
                    var source = promisesResult.sources.data.find(function(s) {
                        return (s.sourceId === association.sourceId && s.sourceType === association.sourceType);
                    });
                    var filter = promisesResult.filters.data.find(function(f) {return f.id === association.tagFilterId;});
                    association.sourceName = source.sourceName;
                    association.filterName = filter.name;
                    association.filterType = filter.tagFilterType;
                    return association;
                });

                return resultArray;
            });

            return combinedPromise;
        };

        $scope.loadFilters = function(startIndex, count, orderByProperty, orderByDirection) {
            return $http.get('/api/filtering/tagfilter?startindex=' + startIndex + '&count=' + count);
        };

        $scope.addAssociationButtonClicked = function() {
            openAddEntityModal('addSourceFilterAssociationModalContent.html', 'AddAssociationModalCtrl', '/api/filtering/associations', 'Source Filter Association', $scope.callbacks.associationsTable);
        };

        $scope.addFilterButtonClicked = function() {
            openAddEntityModal('addFilterModalContent.html', 'AddFilterModalCtrl', '/api/filtering/tagfilter', 'Tag Filter', $scope.callbacks.filtersTable);
        };

        $scope.filterSelected = function(filter) {
            $scope.uiState.selectedFilter = filter;
        };

        function confirmDeleteFilter(filters) {
            var f = openDeleteEntitiesModalFunction('/api/filtering/tagfilter/', 'filter(s)');
            f(filters).finally(function() {
                if ($scope.uiState.selectedFilter &&
                    filters.map(function(x) {return x.id;}).includes($scope.uiState.selectedFilter.id)) {
                    $scope.uiState.selectedFilter = null;
                }
                $scope.callbacks.filtersTable.clearSelection();
                $scope.callbacks.filtersTable.reloadPage();
                $scope.callbacks.filterAttributesTables.reloadPage();
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
            return $http.get("/api/filtering/tagfilter?startindex=0&count=1000000").success(function (filters) {
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

        $scope.attributeActions = [
            {
                name: 'Remove',
                action: removeAttributes
            }
        ];

        $scope.$watch('uiState.selectedFilter', function() {
            resetState();
        });

        // Scope functions
        $scope.filterChanged = function() {
            if ($scope.state.filterSpec && $scope.uiState.selectedFilter.id !== $scope.state.filterSpec.id) {
                return true;
            }
            return !angular.equals($scope.state.originalFilterSpec, $scope.state.filterSpec);
        };

        $scope.addFilterAttributeButtonClicked = function() {
            var newAttribute = {};

            var dialogPromise = $mdDialog.show({
                templateUrl: '/assets/partials/editFilterAttributeModalContent.html',
                controller: 'EditSeriesTypeRuleAttributeModalCtrl',
                locals: {
                    attribute: newAttribute
                }
            });

            dialogPromise.then(function (response) {
                if ($scope.state.filterSpec.tags.map(function(t) {return t.tag;}).includes(newAttribute.tag)) //Don't include tag twice
                    return;
                $scope.state.filterSpec.tags.push(newAttribute);
                $scope.state.filterSpec.tags = $scope.state.filterSpec.tags.sort(function(a, b) {return a.tag - b.tag;});
                $scope.callbacks.filterAttributesTables.reloadPage();
            });
        };


        $scope.loadFilterAttributes = function(startIndex, count) {
            if ($scope.state.filterSpec)
                return ($scope.state.filterSpec.tags.slice(startIndex, startIndex + count) || []);
            return [];
        };

        $scope.loadSeriesTypeRuleAttributes = function(rule) {
            var attributes;

            if (!rule.attributes) {
                return undefined;
            }

            // Return copy of array to avoid side effects when the attributes array is updated
            return rule.attributes.slice(0);
        };

        $scope.saveButtonClicked = function () {
            var savePromise;
            var isCreate;

            if ($scope.uiState.selectedFilter.id === -1) {
                isCreate = true;
                savePromise = $http.post('/api/filtering/tagfilter', $scope.state.filterSpec);
            } else {
                isCreate = false;
                savePromise = $http.post('/api/filtering/tagfilter', $scope.state.filterSpec);
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
                $scope.callbacks.filterAttributesTables.reloadPage();

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

        function loadFilter() {
            if (!$scope.uiState.selectedFilter ||
                $scope.uiState.selectedFilter.id === -1) {
                return;
            }

            $http.get('/api/filtering/tagfilter/' + $scope.uiState.selectedFilter.id)
                .success(function(filter) {
                    $scope.state.filterSpec = filter;
                    $scope.state.originalFilterSpec = angular.copy(filter);
                    $scope.callbacks.filterAttributesTables.reloadPage();
                })
                .error(function(error) {
                    sbxToast.showErrorMessage('Failed to load filter: ' + error);
                });
        }

        function removeAttributes(attributes) {
            var attributeIndex;

            angular.forEach(attributes, function(attribute) {

                attributeIndex = $scope.state.filterSpec.tags.indexOf(attribute);
                if (attributeIndex >= 0) {
                    $scope.state.filterSpec.tags.splice(attributeIndex, 1);
                }
            });
            $scope.callbacks.filterAttributesTables.clearSelection();
        }
    })

    .controller('EditFilterAttributeModalCtrl', function($scope, $mdDialog, attribute) {
        // Initialization
        $scope.attribute = attribute;

        // Scope functions
        $scope.cancelButtonClicked = function() {
            $mdDialog.cancel();
        };

        $scope.saveAttribute = function() {
            if ($scope.editAttributeForm.$invalid) {
                return;
            }

            $mdDialog.hide();
        };
    })

    .controller('AddFilterModalCtrl', function($scope, $mdDialog) {
    // Initialization
    $scope.filterType = 'WHITELIST';

    // Scope functions
    $scope.addButtonClicked = function() {
        $mdDialog.hide({ id: -1, name: $scope.filterName, tagFilterType: $scope.filterType, tags: []});
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});