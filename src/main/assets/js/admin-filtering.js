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

    .controller('AdminFilteringCtrl', function($scope, $http, $mdDialog, openAddEntityModal, openDeleteEntitiesModalFunction) {
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
                    action: openDeleteEntitiesModalFunction('/api/filtering/tagfilter/', 'filter(s)')
                }
            ];

        $scope.callbacks = {};

        // Scope functions
        $scope.loadSourceFilterAssociations = function(startIndex, count, orderByProperty, orderByDirection) {
            return $http.get('/api/filtering/associations?startindex=' + startIndex + '&count=' + count);
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
        $scope.callbacks.filterAttributesTables = {};
        $scope.state = {
            filterSpec: null
        };

        $scope.attributeActions = [
            {
                name: 'Remove',
                action: removeAttribues
            }
        ];

        $scope.$watch('uiState.selectedFilter', function() {
            resetState();
        });

        // Scope functions
        $scope.seriesTypeDataChanged = function() {
            var rulesHaveChanged = false;
            var attributesHaveChnaged = false;

            if (angular.equals($scope.uiState.selectedSeriesType, $scope.originalSeriesType) === false) {
                return true;
            }

            angular.forEach($scope.state.rules, function(rule) {
                if (isRuleDirty(rule)) {
                    rulesHaveChanged = true;
                }
            });

            return rulesHaveChanged;
        };

        $scope.addRuleButtonClicked = function() {
            $scope.state.rules.push({
                attributes: [],
                originalAttributes: []
            });

            $scope.callbacks.ruleAttributesTables.push({});
        };

        $scope.addRuleAttributeButtonClicked = function() {
            var newAttribute = {
                //seriesTypeRuleId: rule.id
            };

            var dialogPromise = $mdDialog.show({
                templateUrl: '/assets/partials/editSeriesTypeRuleAttributeModalContent.html',
                controller: 'EditSeriesTypeRuleAttributeModalCtrl',
                locals: {
                    attribute: newAttribute
                }
            });

            dialogPromise.then(function (response) {
                $scope.filterSpec.tags.push(newAttribute);
                $scope.callbacks.filterAttributesTables.reloadPage();
            });
        };


        $scope.loadFilterAttributes = function() {
            if ($scope.state.filterSpec)
                return ($scope.state.filterSpec.tags || []);
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

            if ($scope.seriesTypeForm.$invalid) {
                return;
            }

            if ($scope.uiState.selectedSeriesType.id === -1) {
                isCreate = true;
                savePromise = $http.post('/api/seriestypes', $scope.uiState.selectedSeriesType);
            } else {
                isCreate = false;
                savePromise = $http.put('/api/seriestypes/' + $scope.uiState.selectedSeriesType.id, $scope.uiState.selectedSeriesType);
            }

            savePromise = savePromise.then(function(response) {
                if (response.data.id) {
                    $scope.uiState.selectedSeriesType.id = response.data.id;
                }

                return saveRules();
            });

            savePromise.then(function() {
                if (isCreate) {
                    sbxToast.showInfoMessage("Series type added");
                } else {
                    sbxToast.showInfoMessage("Series type updated");
                }

                resetState();
                $scope.callbacks.seriesTypesTable.reloadPage();
            }, function(error) {
                sbxToast.showErrorMessage(error);
            });

            return savePromise;
        };

        // Private functions
        function isRuleDirty(rule) {
            if (!angular.equals(rule.attributes, rule.originalAttributes)) {
                return true;
            }
        }

        function resetState() {
            // $scope.seriesTypeForm.$setPristine();
            console.log('callbacks= ' + $scope.callbacks);
            // $scope.originalSeriesType = angular.copy($scope.uiState.selectedSeriesType);
            //
             $scope.state.filterSpec = null;
            // $scope.callbacks.ruleAttributesTables = [];
            //
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
                    $scope.callbacks.filterAttributesTables.reloadPage();
                    // handleLoadedRules(rules);
                })
                .error(function(error) {
                    sbxToast.showErrorMessage('Failed to load filter: ' + error);
                });
        }

        // function handleLoadedRules(rules) {
        //     angular.forEach(rules, function(rule) {
        //         // Selected series type may have changed while the rules were loaded
        //         if (rule.seriesTypeId === $scope.uiState.selectedSeriesType.id) {
        //             $scope.state.rules.push(rule);
        //             loadRuleAttributes(rule);
        //         }
        //     });
        // }

        function loadRuleAttributes(rule) {
            $http.get('/api/seriestypes/rules/' + rule.id + '/attributes')
                .success(function(attributes) {
                    rule.attributes = attributes;
                    rule.originalAttributes = angular.copy(attributes);
                })
                .error(function(error) {
                    sbxToast.showErrorMessage('Failed to load rule attributes: ' + error);
                });
        }

        function saveRules() {
            var saveRulePromises = [];
            var savePromise;

            angular.forEach($scope.state.rules, function(rule) {
                savePromise = null;

                if (rule.attributes && rule.attributes.length === 0 && rule.id) {
                    savePromise = deleteRule(rule);
                } else if (!angular.equals(rule.attributes, rule.originalAttributes)) {
                    if (!rule.id) {
                        savePromise = createRule(rule);
                    } else {
                        savePromise = saveRuleAttributes(rule, rule.attributes, rule.originalAttributes);
                    }
                }

                if (savePromise) {
                    saveRulePromises.push(savePromise);
                }
            });

            return $q.all(saveRulePromises);
        }

        function deleteRule(rule) {
            return $http.delete('/api/seriestypes/rules/' + rule.id);
        }

        function createRule(rule) {
            var savePromise = $http.post('/api/seriestypes/rules', { id: -1, seriesTypeId: $scope.uiState.selectedSeriesType.id });

            savePromise = savePromise.then(function(response) {
                return saveRuleAttributes(response.data, rule.attributes, rule.originalAttributes);
            });

            return savePromise;
        }

        function saveRuleAttributes(rule, attributes, originalAttributes) {
            var promises = [];

            var diff = attributesArraysDiff(attributes, originalAttributes);

            promises.push(createNewAttributes(rule, diff.newAttributes));
            promises.push(deleteRemovedAttributes(rule, diff.removedAttributes));

            return $q.all(promises);
        }

        function attributesArraysDiff(attributes, originalAttributes) {
            var newAttributes = [];
            var removedAttributes = [];
            var tempAttribute;

            angular.forEach(attributes, function(attribute) {
                tempAttribute = findObjectWithIdInArray(attribute.id, originalAttributes);
                if (!tempAttribute) {
                    newAttributes.push(attribute);
                }
            });

            angular.forEach(originalAttributes, function(originalAttribute) {
                tempAttribute = findObjectWithIdInArray(originalAttribute.id, attributes);
                if (!tempAttribute) {
                    removedAttributes.push(originalAttribute);
                }
            });

            return {
                newAttributes: newAttributes,
                removedAttributes: removedAttributes
            };
        }

        function findObjectWithIdInArray(id, array) {
            var result;

            if (!id) {
                return undefined;
            }

            angular.forEach(array, function(object) {
                if (object.id === id) {
                    result = object;
                }
            });

            return result;
        }

        function createNewAttributes(rule, newAttributes) {
            var saveAttributePromises = [];
            var savePromise;

            angular.forEach(newAttributes, function(attribute) {
                newAttribute = {
                    id: -1,
                    seriesTypeRuleId: rule.id,
                    tag: attribute.tag,
                    name: "",
                    values: attribute.values
                };

                savePromise = $http.post('/api/seriestypes/rules/' + rule.id + '/attributes', newAttribute);

                saveAttributePromises.push(savePromise);
            });

            return $q.all(saveAttributePromises);
        }

        function deleteRemovedAttributes(rule, removedAttributes) {
            var deleteAttributePromises = [];
            var deletePromise;

            angular.forEach(removedAttributes, function(attribute) {
                deletePromise = $http.delete('/api/seriestypes/rules/' + rule.id + '/attributes/' + attribute.id);

                deleteAttributePromises.push(deletePromise);
            });

            return $q.all(deleteAttributePromises);
        }

        function removeAttribues(attributes) {
            var rule;
            var attributeIndex;

            angular.forEach(attributes, function(attribute) {
                rule = findRuleForAttribute(attribute);

                attributeIndex = rule.attributes.indexOf(attribute);
                if (attributeIndex >= 0) {
                    rule.attributes.splice(attributeIndex, 1);
                }
            });
        }

        function findRuleForAttribute(attribute) {
            var result;

            angular.forEach($scope.state.rules, function(rule) {
                if (rule.attributes && rule.attributes.indexOf(attribute) >= 0) {
                    result = rule;
                }
            });

            return result;
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
    });