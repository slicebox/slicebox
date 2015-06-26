/*jshint multistr: true */
/*jshint evil: true */

angular.module('slicebox.directives', [])

.directive('sbxChip', function() {
    return {
        restrict: 'E',
        transclude: true,
        template: '<span class="sbx-chip {{chipClass}}" ng-transclude></span>',
        scope: {
            chipClass: '@'
        }
    };
})

.directive('sbxButton', function($q, $timeout) {
	
    return {
        restrict: 'E',
        template: '<md-button type="{{buttonType}}" ng-class="buttonClass" ng-click="buttonClicked()" ng-disabled="buttonDisabled || disabled">' + 
                    '{{buttonTitle}} <i ng-if="disabled && showSpinner" class="fa fa-lg fa-spinner fa-spin">' + 
                    '</md-button>',
        scope: {
            action: '&',
            buttonType: '@',
            buttonTitle: '@',
            buttonDisabled: '=',
            buttonClass: '@'
        },
        link: function($scope, $element, $attrs) {

            if (angular.isUndefined($attrs.buttonType)) {
                $scope.buttonType = 'button';
            }

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
 .directive('sbxGrid', function($filter, $q, $timeout) {

    return {
        restrict: 'E',
        templateUrl: '/assets/partials/directives/sbxGrid.html',
        transclude: true,
        scope: {
            loadPage: '&',
            converter: '&',
            pageSize: '=',
            objectSelectedCallback: '&objectSelected',
            objectActions: '=',
            sorting: '=',
            filter: '=',
            callbacks: '=',
            rowCSSClassesCallback: '&rowCssClasses',
            rowObjectActionsCallback: '&rowObjectActions',
            emptyMessage: '@'
        },
        controller: function($scope, $element, $attrs) {
            $scope.columnDefinitions = [];

            this.addColumn = function(columnDefinition) {
                $scope.columnDefinitions.push(columnDefinition);
            };
        },
        link: function($scope, $element, $attrs) {

            // Initialization

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
            $scope.objectActionSelection = [];
            $scope.uiState = {
                objectActionsDropdownOpen: false,
                selectAllChecked: false,
                pageSizeOpen: false,
                emptyMessage: 'Empty',
                filter: '',
                selectedObjectAction: undefined
            };

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

            $scope.$watchCollection('columnDefinitions', function() {
                doOnColumnsChanged();
            });            

            $scope.$watch('pageSize', function() {
                loadPageData();
            });

            $scope.$watchCollection('objectActionSelection', function() {
                updateSelectAllObjectActionChecked();
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
                for (var i = 0; i < $scope.objectActionSelection.length; i++) {
                    $scope.objectActionSelection[i] = $scope.uiState.selectAllChecked;
                }
            };

            $scope.objectActionsEnabled = function() {
                for (var i = 0; i < $scope.objectActionSelection.length; i++) {
                    if ($scope.objectActionSelection[i] === true) {
                        return true;
                    }
                }

                return false;
            };

            $scope.objectActionEnabled = function(objectAction) {
                if (!$scope.objectActionsEnabled()) {
                    return false;
                }

                if (angular.isDefined(objectAction.requiredSelectionCount)) {
                    return (objectAction.requiredSelectionCount === selectedActionObjects().length);
                }

                return true;
            };

            $scope.objectActionSelected = function() {
                var objectAction = $scope.uiState.selectedObjectAction;
                $scope.uiState.selectedObjectAction = undefined;

                performObjectAction(objectAction);
            };

            $scope.pageSizeChanged = function(size) {
                $scope.currentPageSize = size;
                $scope.uiState.pageSizeOpen = false;
                loadPageData();
            };

            // Private functions
            function doOnColumnsChanged() {

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

            function loadPageData() {
                if ($scope.currentPageSize <= 0) {
                    return $q.when([]);
                }

                var selectedObjectsBeforeLoadPage = selectedActionObjects();

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
                        handleLoadedPageData(response, selectedObjectsBeforeLoadPage);
                    } else {
                            // Assume response is a http response and extract data
                            handleLoadedPageData(response.data, selectedObjectsBeforeLoadPage);
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

            function handleLoadedPageData(pageData, selectedObjectsBeforeLoadPage) {
                $scope.objectList = convertPageData(pageData);

                if ($scope.objectList.length > $scope.currentPageSize) {
                    // Remove the extra object from the end of the list
                    $scope.objectList.splice(-1, 1);
                    $scope.morePagesExists = true;
                } else {
                    $scope.morePagesExists = false;
                }

                validateAndUpdateSelectedObject();

                validateAndUpdateObjectActionSelection(selectedObjectsBeforeLoadPage);

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
                if (columnDefinition.property && columnDefinition.property.indexOf('[') !== -1) {
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

            function validateAndUpdateObjectActionSelection(selectedObjectsBeforeLoadPage) {
                $scope.objectActionSelection = new Array($scope.objectList.length);

                if (selectedObjectsBeforeLoadPage.length === 0) {
                    return;
                }

                angular.forEach(selectedObjectsBeforeLoadPage, function(selectedObjectBeforeLoadPage) {
                    var newSelectedObject = findObjectInArray(selectedObjectBeforeLoadPage, $scope.objectList);
                    if (newSelectedObject) {
                        var index = $scope.objectList.indexOf(newSelectedObject);
                        if (index >= 0) {
                            $scope.objectActionSelection[index] = true;
                        }
                    }
                });
            }

            function updateSelectAllObjectActionChecked() {
                var allSelected = ($scope.objectActionSelection.length > 0);

                for (var i = 0; i < $scope.objectActionSelection.length; i++) {
                    if (!$scope.objectActionSelection[i]) {
                        allSelected = false;
                    }
                }

                $scope.uiState.selectAllChecked = allSelected;
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
                var selectedObjects = [];

                for (var i = 0; i < $scope.objectActionSelection.length; i++) {
                    if ($scope.objectActionSelection[i]) {
                        selectedObjects.push($scope.objectList[i]);
                    }
                }

                return selectedObjects;
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

            function performObjectAction(objectAction) {
                if (!$scope.objectActionEnabled(objectAction)) {
                    $event.stopPropagation();
                    return;
                }

                if (angular.isFunction(objectAction.action)) {
                    var objectActionResult = objectAction.action(selectedActionObjects());

                    $q.when(objectActionResult).finally(function() {
                        loadPageData();
                    });
                } else {
                    throwError('TypeError', 'An object action must define an action function: ' + angular.toJson(objectAction));
                }

                $scope.uiState.objectActionsDropdownOpen = false;
            }
        }
    };

})

.directive('sbxGridColumn', function() {

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
    
})

.directive('sbxGridCell', function() {
    
    return {
        require: '^sbxGridColumn',
        restrict: 'E',
        transclude: true,
        link: function($scope, $element, $attrs, sbxGridColumnController, $transclude) {
            sbxGridColumnController.setRenderer($transclude, $scope);
        }
        
    };
    
})

.directive('sbxGridInternalTd', function() {
    
    return {
        restrict: 'A',
        priority: 500, // Must be below ngRepeat priority
        link: function($scope, $element, $attrs) {
            var property = $scope.columnDefinition.property;
            var rendererTranscludeFn = $scope.columnDefinition.rendererTranscludeFn;
            var rendererScope = $scope.columnDefinition.rendererScope;
            var rendererChildScope = null;
            var rawPropertyValue = null;

            if (angular.isFunction(rendererTranscludeFn)) {
                // Remove default rendering
                $element.empty();

                rendererChildScope = rendererScope.$new();
                rendererChildScope.rowObject = $scope.rowObject;

                $scope.$on('$destroy', function() {
                    rendererChildScope.$destroy();
                });

                rawPropertyValue = $scope.rowObject[$scope.columnDefinition.property];
                if ($scope.columnDefinition.property && $scope.columnDefinition.property.indexOf('[') !== -1) {
                    rawPropertyValue = eval('$scope.rowObject.' + $scope.columnDefinition.property);
                }

                rendererChildScope.rawPropertyValue = rawPropertyValue;
                rendererChildScope.filteredPropertyValue = $scope.filteredCellValues[$scope.$parent.$index][$scope.columnDefinition.property];

                rendererTranscludeFn(rendererChildScope, function(rendererElement) {
                    $element.append(rendererElement);
                });
            }
        }
        
    };
    
});