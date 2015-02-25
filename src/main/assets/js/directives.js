/*jshint multistr: true */
/*jshint evil: true */

angular.module('slicebox.directives', [])

.directive('sbxButton', function($q, $timeout) {
	
    return {
        restrict: 'E',
        replace: true,
        template: '<button type="button" ng-click="buttonClicked()" ng-disabled="buttonDisabled || disabled">{{buttonTitle}} <img src="/assets/images/spinner.gif" ng-if="disabled && showSpinner" /></button>',
        scope: {
            action: '&',
            buttonTitle: '@',
            buttonDisabled: '='
        },
        link: function($scope, $element, $attrs) {

            var spinnerTimeoutPromise;

            $scope.buttonClicked = function() {

                $scope.showSpinner = false;
                $scope.disabled = true;

                spinnerTimeoutPromise = $timeout(function() {
                   $scope.showSpinner = true;
               }, 1000);

                var action = $scope.action();
                $q.when(action).finally(function() {
                   $scope.disabled = false;

                   $timeout.cancel(spinnerTimeoutPromise);
                   spinnerTimeoutPromise = null;
                });
            };
        }
    };
})

/*
 * In order for selection check boxes and object actions to work, all objects in the
 * list must have an id property. 
 */
 .directive('sbxGrid', function($filter, $q, $timeout, $log) {

    return {
        restrict: 'E',
        templateUrl: '/assets/partials/directives/sbxGrid.html',
        transclude: true,
        scope: {
            loadPage: '&',
            converter: '&',
            pageSize: '=',
            pageSizes: '=',
            objectSelectedCallback: '&objectSelected',
            objectActions: '=',
            sorting: '=',
            filter: '=',
            configurationKey: '@',
            callbacks: '=',
            rowCSSClassesCallback: '&rowCssClasses',
            rowObjectActionsCallback: '&rowObjectActions',
            emptyMessage: '@'
        },
        controller: function($scope, $element, $attrs) {
            $scope.columnDefinitions = [];
            $scope.columnConfigurations = {};

            this.addColumn = function(columnDefinition) {
                $scope.columnDefinitions.push(columnDefinition);
                $scope.columnConfigurations[columnDefinition.property] = {};
            };
        },
        link: function($scope, $element, $attrs) {

            // Initialization
            var ColumnsVisibilityPreferenceKey = $scope.configurationKey + '-columnsVisibility';
            var saveColumnsVisibilityPreferencePromise = $q.when(null);

            $scope.cssClasses = {
                empty: {},
                pointerCursor: {
                    cursor: 'pointer'
                },
                orderBy: {
                    ascending: {
                        'fa': true,
                        'fa-fw': true,
                        'pull-right': true,
                        'fa-sort-up': true
                    },
                    descending: {
                        'fa': true,
                        'fa-fw': true,
                        'pull-right': true,
                        'fa-sort-down': true
                    }
                },
                rowSelected: {
                    'row-selected': true
                }
            };

            $scope.currentPage = 0;
            $scope.currentPageSize = pageSizeAsNumber();
            $scope.selectedObject = null;
            $scope.orderByProperty = null;
            $scope.orderByDirection = null;
            $scope.objectsSelectedForAction = [];
            $scope.uiState = {
                objectActionsDropdownOpen: false,
                selectAllChecked: false,
                configurationDropdownOpen: false,
                pageSizeOpen: false,
                emptyMessage: 'Empty',
                filter: ''
            };
            $scope.visibleColumnsPreferenceValue = undefined;

            if (angular.isDefined($attrs.callbacks)) {
                $scope.callbacks = {
                    reset: reset,
                    reloadPage: loadPageData,
                    selectedActionObjects: selectedActionObjects,
                    clearSelection: clearSelection
                };
            }

            if (angular.isDefined($attrs.emptyMessage)) {
                $scope.uiState.emptyMessage = $scope.emptyMessage;
            }

            loadColumnVisibilityPreference();

            $scope.$watchCollection('columnDefinitions', function() {
                doOnColumnsChanged();
            });            

            $scope.$watch('pageSize', function() {
                loadPageData();
            });

            $scope.$watchCollection('visibleColumnsPreferenceValue', function() {
                updateColumnsVisibility();
            });

            // Scope functions
            $scope.tableBodyStyle = function() {
                if (selectionEnabled()) {
                    return $scope.cssClasses.pointerCursor;
                }

                return $scope.cssClasses.empty;
            };

            $scope.columnHeaderOrderByClass = function(columnDefinition) {
                var orderByClass = $scope.cssClasses.empty;

                if ($scope.orderByProperty === columnDefinition.property) {
                    if ($scope.orderByDirection === 'ASCENDING') {
                        orderByClass = $scope.cssClasses.orderBy.ascending;
                    } else {
                        orderByClass = $scope.cssClasses.orderBy.descending;
                    }
                }

                return orderByClass;
            };

            $scope.rowCSSClasses = function(rowObject) {
                var rowObjectSelected = rowObject === $scope.selectedObject;

                if (angular.isDefined($attrs.rowCssClasses)) {
                    return $scope.rowCSSClassesCallback({rowObject: rowObject, selected: rowObjectSelected});
                }

                if (selectionEnabled() && rowObjectSelected) {
                    return $scope.cssClasses.rowSelected;
                }

                return $scope.cssClasses.empty;
            };

            $scope.rowHasActions = function(rowObject) {
                if (angular.isUndefined($attrs.rowObjectActions)) {
                    return true;
                }

                return $scope.rowObjectActionsCallback({rowObject: rowObject}).length > 0;
            };

            $scope.columnVisibilityCheckboxChanged = function() {
                saveColumnsVisibilityPreferencePromise.finally(
                    // Wait for previous preference save to finish to avoid out of order saves
                    function() {
                        saveColumnsVisibilityPreference();
                    });
            };

            $scope.loadNextPage = function() {
                if ($scope.objectList.length < $scope.currentPageSize) {
                    return;
                }

                $scope.currentPage += 1;
                loadPageData();
            };

            $scope.loadPreviousPage = function() {
                if ($scope.currentPage === 0) {
                    return;
                }

                $scope.currentPage -= 1;
                loadPageData();
            };

            $scope.rowClicked = function(rowObject) {
                if ($scope.selectedObject === rowObject) {
                    selectObject(null);
                } else {
                    selectObject(rowObject);
                }
            };

            $scope.sortingEnabled = function() {
                var sortingEnabled = $scope.sorting;

                if (angular.isUndefined(sortingEnabled)) {
                    sortingEnabled = true;
                }

                return sortingEnabled;
            };

            $scope.filterEnabled = function() {
                return $scope.filter;
            };

            $scope.filterChanged = function() {
                loadPageData();
            };

            $scope.columnClicked = function(columnDefinition) {
                if (!$scope.sortingEnabled()) {
                    return;
                }

                if ($scope.orderByProperty === columnDefinition.property) {
                    if ($scope.orderByDirection === 'ASCENDING') {
                        $scope.orderByDirection = 'DESCENDING';
                    } else {
                        $scope.orderByDirection = 'ASCENDING';
                    }
                } else {
                    $scope.orderByProperty = columnDefinition.property;
                    $scope.orderByDirection = 'ASCENDING';
                }

                loadPageData();
            };

            $scope.selectAllChanged = function() {
                $scope.objectsSelectedForAction = [];

                if ($scope.uiState.selectAllChecked) {
                    for (i = 0, length = $scope.objectList.length; i < length; i++) {
                        $scope.objectsSelectedForAction.push($scope.objectList[i]);
                    }
                }
            };

            $scope.rowSelectionCheckboxClicked = function(rowObject) {
                var rowObjectIndex = $scope.objectsSelectedForAction.indexOf(rowObject);
                if (rowObjectIndex == -1) {
                    $scope.objectsSelectedForAction.push(rowObject);
                } else {
                    $scope.objectsSelectedForAction.splice(rowObjectIndex, 1);
                }

                validateAndUpdateObjectActionSelections();
            };

            $scope.rowObjectSelected = function(rowObject) {
                var rowObjectIndex = $scope.objectsSelectedForAction.indexOf(rowObject);

                return rowObjectIndex >= 0;
            };

            $scope.objectActionsEnabled = function() {
                return $scope.objectsSelectedForAction.length > 0;
            };

            $scope.performObjectAction = function(objectAction) {
                if (angular.isFunction(objectAction.action)) {
                    var objectActionResult = objectAction.action(selectedActionObjects());

                    $q.when(objectActionResult).finally(function() {
                        loadPageData();
                    });
                } else {
                    throwError('TypeError', 'An object action must define an action function: ' + angular.toJson(objectAction));
                }

                $scope.uiState.objectActionsDropdownOpen = false;
            };

            $scope.columnConfigurationEnabled = function() {
                return angular.isDefined($attrs.configurationKey);
            };

            $scope.pageSizeChanged = function(size) {
                $scope.currentPageSize = size;
                $scope.uiState.pageSizeOpen = false;
                loadPageData();
            };

            // Private functions
            function updateColumnsVisibility() {
                angular.forEach($scope.columnDefinitions, function(columnDefinition) {
                    updateVisibilityForColumn(columnDefinition);
                });
            }

            function doOnColumnsChanged() {
                $scope.columnMetaDatas = {};

                angular.forEach($scope.columnDefinitions, function(columnDefinition) {
                    updateVisibilityForColumn(columnDefinition);
                });

                calculateFilteredCellValues();

                // Need to reset page data to trigger rerendering of rows
                var savedObjectList = $scope.objectList;
                $scope.objectList = null;
                $timeout(function() {
                    if ($scope.objectList === null) {
                        $scope.objectList = savedObjectList;
                    }
                });
            }

            function updateVisibilityForColumn(columnDefinition) {
                // if (angular.isUndefined($scope.visibleColumnsPreferenceValue)) {
                //     // Visibility user preference not loaded yet
                //     return;
                // }

                $scope.columnConfigurations[columnDefinition.property].visible =
                ($scope.visibleColumnsPreferenceValue === null ||
                    $scope.visibleColumnsPreferenceValue.indexOf(columnDefinition.property) != -1);
            }

            function loadColumnVisibilityPreference() {
                // TODO: load preference

                if (angular.isUndefined($attrs.configurationKey)) {
                    $scope.visibleColumnsPreferenceValue = null;
                    return;
                }

                // exiniUserPreferences.loadUserPreference(ColumnsVisibilityPreferenceKey).then(function(preferenceValue) {
                //     if (angular.isDefined(preferenceValue)) {
                //         $scope.visibleColumnsPreferenceValue = preferenceValue;
                //     } else {
                //         $scope.visibleColumnsPreferenceValue = null;
                //     }
                // }, function() {
                //     // Retry on error
                //     loadColumnVisibilityPreference();
                // });
            }

            function saveColumnsVisibilityPreference() {
                // TODO: save preference

                // var visibleColumnProperties = [];

                // angular.forEach($scope.columnConfigurations, function(configuration, columnProperty) {
                //     if (configuration && configuration.visible) {
                //         visibleColumnProperties.push(columnProperty);
                //     }
                // });

                // saveColumnsVisibilityPreferencePromise =
                //     exiniUserPreferences.saveUserPreference(ColumnsVisibilityPreferenceKey, visibleColumnProperties);
            }

            function loadPageData() {
                if ($scope.currentPageSize <= 0) {
                    return $q.when([]);
                }

                var deferred = $q.defer();

                var startIndex = $scope.currentPage * $scope.currentPageSize;

                // Load one more object than pageSize to be able to check if more data is available
                var count = $scope.currentPageSize + 1;
                var loadPageFunctionParameters = {
                    startIndex: startIndex,
                    count: count,
                    orderByProperty: $scope.orderByProperty,
                    orderByDirection: $scope.orderByDirection
                };

                if ($scope.uiState.filter && $scope.uiState.filter.length > 0) {
                    loadPageFunctionParameters.filter = $scope.uiState.filter;
                }

                var loadPageResponse = ($scope.loadPage || angular.noop)(loadPageFunctionParameters);

                $q.when(loadPageResponse).then(function(response) {
                    if (angular.isArray(response)) {
                        handleLoadedPageData(response);
                    } else {
                            // Assume response is a http response and extract data
                            handleLoadedPageData(response.data);
                        }
                        
                        deferred.resolve();
                    });

                return deferred.promise;
            }

            function pageSizeAsNumber() {
                var pageSizeNumber = null;

                if (angular.isDefined($scope.pageSize)) {
                    pageSizeNumber = parseInt($scope.pageSize);
                } else {
                    pageSizeNumber = 0;
                }

                return pageSizeNumber;
            }

            function handleLoadedPageData(pageData) {
                $scope.objectList = convertPageData(pageData);

                if ($scope.objectList.length > $scope.currentPageSize) {
                    // Remove the extra object from the end of the list
                    $scope.objectList.splice(-1, 1);
                    $scope.morePagesExists = true;
                } else {
                    $scope.morePagesExists = false;
                }

                validateAndUpdateSelectedObject();
                validateAndUpdateObjectActionSelections();

                if ($scope.objectList.length === 0) {
                    // Avoid empty pages
                    $scope.loadPreviousPage();
                }

                calculateFilteredCellValues();
            }

            function convertPageData(pageData) {
                var result = null;

                if (angular.isDefined($attrs.converter)) {
                    result = $scope.converter({pageData: pageData});
                    if (!angular.isArray(result)) {
                        throwError('PageDataError', 'Converter must return an array');
                    }
                } else if (angular.isArray(pageData)) {
                    result = pageData;
                } else {
                    throwError('PageDataError', 'Unknown format for page data. Consider setting a converter.\n' + angular.toJson(pageData));
                }

                return result;
            }

            function calculateFilteredCellValues() {
                $scope.filteredCellValues = [];

                if (!$scope.objectList ||
                    $scope.objectList.length === 0) {
                    return;
                }

                angular.forEach($scope.objectList, function(rowObject) {
                    $scope.filteredCellValues.push({});
                });

                angular.forEach($scope.columnDefinitions, function(columnDefinition) {
                    calculateFilteredCellValuesForColumn(columnDefinition);
                });
            }

            function calculateFilteredCellValuesForColumn(columnDefinition) {
                angular.forEach($scope.objectList, function(rowObject, index) {
                    $scope.filteredCellValues[index][columnDefinition.property] = calculateFilteredCellValueForRowObjectAndColumn(rowObject, columnDefinition);
                });
            }

            function calculateFilteredCellValueForRowObjectAndColumn(rowObject, columnDefinition) {
                var cellValue = rowObject[columnDefinition.property];
                if (columnDefinition.property.indexOf('[') !== -1) {
                    cellValue = eval('rowObject.' + columnDefinition.property);
                }
                var filter = columnDefinition.filter;

                if (!filter) {
                    return cellValue;
                }

                if (!angular.isString(filter)) {
                    throwError('TypeError', 'Invalid filter value, must be a string: ' + angular.toJson(filter));
                    return cellValue;
                }

                var filterSeparatorIndex = filter.indexOf(':');
                var filterName = filter;
                var filterParams = null;

                if (filterSeparatorIndex != -1) {
                    filterName = filter.substring(0, filterSeparatorIndex).trim();
                    filterParams = filter.substring(filterSeparatorIndex + 1).trim();
                }

                if (filterParams) {
                    return $filter(filterName)(cellValue, filterParams);
                } else {
                    return $filter(filterName)(cellValue);
                }
            }

            function validateAndUpdateSelectedObject() {
                if (!$scope.selectedObject) {
                    return;
                }

                var newSelectedObject = findObjectInArray($scope.selectedObject, $scope.objectList);
                if (newSelectedObject) {
                    $scope.selectedObject = newSelectedObject;
                } else {
                    selectObject(null);
                }
            }

            function validateAndUpdateObjectActionSelections() {
                var updatedObjectsSelectedForAction = [];
                var selectionCount = 0;

                angular.forEach($scope.objectList, function(object) {
                    if (findSelectedActionObjectWithId(object.id) !== null) {
                        updatedObjectsSelectedForAction.push(object);
                        ++selectionCount;
                    }
                });

                $scope.objectsSelectedForAction = updatedObjectsSelectedForAction;

                if (selectionCount === 0 || selectionCount < $scope.objectList.length) {
                    $scope.uiState.selectAllChecked = false;
                } else if (selectionCount == $scope.objectList.length) {
                    $scope.uiState.selectAllChecked = true;
                }
            }

            function findSelectedActionObjectWithId(objectId) {
                var matchingObject = null;

                angular.forEach($scope.objectsSelectedForAction, function(object) {
                    if (object.id === objectId) {
                        matchingObject = object;
                    }
                });

                return matchingObject;
            }

            function selectionEnabled() {
                return angular.isDefined($attrs.objectSelected);
            }

            function selectObject(object) {
                if ($scope.selectedObject === object) {
                    return;
                }

                $scope.objectSelectedCallback({object: object});

                $scope.selectedObject = object;
            }

            function selectedActionObjects() {
                return $scope.objectsSelectedForAction;
            }

            function typeOfObject(object) {
                if (angular.isDate(object)) {
                    return 'date';
                }
                if (angular.isNumber(object)) {
                    return 'number';
                }
                if (angular.isArray(object)) {
                    return 'array';
                }
                if (angular.isString(object)) {
                    return 'text';
                }
                if (angular.isObject(object)) {
                    return 'object';
                }
            }

            function findObjectInArray(object, objectsArray) {
                for (i = 0, length = objectsArray.length; i < length; i++) {
                    var eachObject = objectsArray[i];

                    if (eachObject === object) {
                        return eachObject;
                    }

                    if (angular.isDefined(eachObject.id) &&
                        angular.isDefined(object.id) &&
                        eachObject.id === object.id) {
                        return eachObject;
                    }
                }

                return null;
            }

            function findObjectByPropertyInArray(objectPropertyName, objectPropertyValue, objectsArray) {
                for (i = 0, length = objectsArray.length; i < length; i++) {
                    var eachObject = objectsArray[i];

                    if (angular.isDefined(eachObject[objectPropertyName]) &&
                        eachObject[objectPropertyName] === objectPropertyValue) {
                        return eachObject;
                    }
                }

                return null;
            }

            function throwError(name, message) {
                throw {
                    name: name,
                    message: message
                };
            }

            function reset() {
                $scope.currentPage = 0;
                return loadPageData();
            }

            function clearSelection() {
                $scope.selectedObject = null;
            }
        }
    };

})

.directive('sbxGridColumn', function($q, $log) {

    return {
        require: '^sbxGrid',
        restrict: 'E',
        transclude: true,
        scope: {
            property: '@',
            type: '=',
            title: '@',
            filter: '@'
        },
        controller: function($scope, $element, $attrs) {
            this.setRenderer = function(rendererTranscludeFn, rendererScope) {
                $scope.rendererTranscludeFn = rendererTranscludeFn;
                $scope.rendererScope = rendererScope;
            };
        },
        link: function($scope, $element, $attrs, sbxGridController, $transclude) {
            // Initialization
            $transclude(function(rendererElement) {
                $element.append(rendererElement);
            });

            var columnDefinition = {
                property: $scope.property,
                title: $scope.title,
                type: $scope.type,
                filter: $scope.filter,
                rendererTranscludeFn: $scope.rendererTranscludeFn,
                rendererScope: $scope.rendererScope
            };

            sbxGridController.addColumn(columnDefinition);
        }        
    };
    
});