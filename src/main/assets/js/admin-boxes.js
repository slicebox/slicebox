(function () {
   'use strict';
}());

angular.module('slicebox.adminBoxes', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/boxes', {
    templateUrl: '/assets/partials/adminBoxes.html',
    controller: 'AdminBoxesCtrl'
  });
})

.controller('AdminBoxesCtrl', function($scope, $http, $modal, $q) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Remove',
                action: removeBoxes
            }
        ];

    $scope.callbacks = {};

    $scope.uiState = {
        errorMessage: null
    };
  
    // Scope functions
    $scope.loadBoxesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/box');
    };

    $scope.generateURLButtonClicked = function() {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/generateURLModalContent.html',
                controller: 'GenerateURLModalCtrl',
                size: 'lg'
            });

        modalInstance.result.then(function (remoteBoxName) {
            $scope.uiState.errorMessage = null;
            $scope.uiState.generateURLInProgress = true;

            var generateURLPromise = $http.post('/api/box/generatebaseurl', {value: remoteBoxName});

            generateURLPromise.success(function(data) {
                showBaseURLDialog(data.value);
            });

            generateURLPromise.error(function(data) {
                $scope.uiState.errorMessage = data.message;
            });

            generateURLPromise.finally(function() {
                $scope.uiState.generateURLInProgress = false;
                $scope.callbacks.boxesTable.reloadPage();
            });
        });
    };

    // Private functions
    function removeBoxes(boxes) {
        var deletePromises = [];
        var deletePromise;

        $scope.uiState.errorMessage = null;

        angular.forEach(boxes, function(box) {
            deletePromise = $http.delete('/api/box/' + box.id);
            deletePromises.push(deletePromise);
        });

        return $q.all(deletePromises);
    }

    function showBaseURLDialog(baseURL) {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/baseURLModalContent.html',
                controller: 'BaseURLModalCtrl',
                size: 'lg',
                resolve: {
                    baseURL: function () {
                        return baseURL;
                    }
                }
            });
    }
})

.controller('GenerateURLModalCtrl', function($scope, $modalInstance) {

    // Scope functions
    $scope.generateButtonClicked = function() {
        $modalInstance.close($scope.remoteBoxName);
    };

    $scope.cancelButtonClicked = function() {
        $modalInstance.dismiss();
    };
})

.controller('BaseURLModalCtrl', function($scope, $modalInstance, baseURL) {
    // Initialization
    $scope.baseURL = baseURL;

    // Scope functions
    $scope.closeButtonClicked = function() {
        $modalInstance.dismiss();
    };
});