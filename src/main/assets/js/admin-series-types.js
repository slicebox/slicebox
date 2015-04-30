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

.controller('SeriesTypeDetailsCtrl', function($scope, $http) {
	// Initialization
	$scope.$watch('uiState.selectedSeriesType', function() {
		$scope.serviceTypeForm.$setPristine();

		$scope.originalServiceType = angular.copy($scope.uiState.selectedSeriesType);
	});

	// Scope functions
	$scope.seriesTypeDataChanged = function() {
		return (angular.equals($scope.uiState.selectedSeriesType, $scope.originalServiceType) === false);
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

		
		savePromise.success(function(data) {
			if (isCreate) {
				$scope.uiState.selectedSeriesType = null;
				$scope.showInfoMessage("Series type added");
			} else {
				$scope.originalServiceType = angular.copy($scope.uiState.selectedSeriesType);
				$scope.showInfoMessage("Series type updated");
			}
			
			$scope.callbacks.seriesTypesTable.reset();
		}).error(function(data) {
			$scope.showErrorMessage(data);                
		});
	};
});