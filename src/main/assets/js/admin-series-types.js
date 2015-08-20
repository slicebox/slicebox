(function () {
   'use strict';
}());

angular.module('slicebox.adminSeriesTypes', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/seriestypes', {
	templateUrl: '/assets/partials/adminSeriesTypes.html',
	controller: 'AdminSeriesTypesCtrl'
  });
})

.controller('AdminSeriesTypesCtrl', function($scope, $http) {
	// Initialization
	$scope.objectActions =
		[
			{
				name: 'Delete',
				action: $scope.confirmDeleteEntitiesFunction('/api/seriestypes/', 'service type(s)')
			}
		];

	$scope.callbacks = {};

	$scope.uiState = {
		selectedSeriesType: null
	};

	// Scope functions
	$scope.loadSeriesTypesPage = function(startIndex, count, orderByProperty, orderByDirection) {
		return $http.get('/api/seriestypes');
	};

	$scope.seriesTypeSelected = function(seriesType) {
		$scope.uiState.selectedSeriesType = seriesType;
	};

	$scope.addSeriesTypeButtonClicked = function() {
		$scope.callbacks.seriesTypesTable.clearSelection();
		
		$scope.uiState.selectedSeriesType = {
			id: -1,
			name: undefined
		};
	};
})

.controller('SeriesTypeDetailsCtrl', function($scope, $http, $mdDialog, $q) {
	// Initialization
	$scope.callbacks.ruleAttributesTables = [];
	$scope.state = {};
	$scope.state.rules = [];

	$scope.attributeActions = [
			{
                name: 'Remove',
                action: removeAttribues
            }
		];

	$scope.$watch('uiState.selectedSeriesType', function() {
		resetState();
	});

	// Scope functions
	$scope.seriesTypeDataChanged = function() {
		var rulesHaveChanged = false;
		var attributesHaveChnaged = false;

		if (angular.equals($scope.uiState.selectedSeriesType, $scope.originalServiceType) === false) {
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

	$scope.addRuleAttributeButtonClicked = function(rule, ruleIndex) {
		var newAttribute = {
			seriesTypeRuleId: rule.id
		};

		var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/editSeriesTypeRuleAttributeModalContent.html',
            controller: 'EditSeriesTypeRuleAttributeModalCtrl',
            locals: {
                    attribute: newAttribute
                }
        });

        dialogPromise.then(function (response) {
            rule.attributes.push(newAttribute);
            $scope.callbacks.ruleAttributesTables[ruleIndex].reloadPage();
        });
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

		if ($scope.serviceTypeForm.$invalid) {
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
				$scope.showInfoMessage("Series type added");
			} else {
				$scope.showInfoMessage("Series type updated");
			}

			resetState();
			$scope.callbacks.seriesTypesTable.reloadPage();
		}, function(error) {
			$scope.showErrorMessage(error);
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
		$scope.serviceTypeForm.$setPristine();

		$scope.originalServiceType = angular.copy($scope.uiState.selectedSeriesType);

		$scope.state.rules = [];
		$scope.callbacks.ruleAttributesTables = [];

		loadRules();
	}

	function loadRules() {
		if (!$scope.uiState.selectedSeriesType ||
			$scope.uiState.selectedSeriesType.id === -1) {
			return;
		}

		$http.get('/api/seriestypes/rules?seriestypeid=' + $scope.uiState.selectedSeriesType.id)
			.success(function(rules) {
				handleLoadedRules(rules);
			})
			.error(function(error) {
				$scope.showErrorMessage('Failed to load rules: ' + error);
			});
	}

	function handleLoadedRules(rules) {
		angular.forEach(rules, function(rule) {
			// Selected series type may have changed while the rules were loaded
			if (rule.seriesTypeId === $scope.uiState.selectedSeriesType.id) {
				$scope.state.rules.push(rule);
				loadRuleAttributes(rule);
			}
		});
	}

	function loadRuleAttributes(rule) {
		$http.get('/api/seriestypes/rules/' + rule.id + '/attributes')
			.success(function(attributes) {
				rule.attributes = attributes;
				rule.originalAttributes = angular.copy(attributes);
			})
			.error(function(error) {
				$scope.showErrorMessage('Failed to load rule attributes: ' + error);
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

.controller('EditSeriesTypeRuleAttributeModalCtrl', function($scope, $mdDialog, attribute) {
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