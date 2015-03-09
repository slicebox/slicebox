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

.controller('AdminBoxesCtrl', function($scope, $http, $modal, $q, $interval, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteBoxes
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.boxesTable)) {
            $scope.callbacks.boxesTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadBoxesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes');
    };

    $scope.connectButtonClicked = function() {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/enterBoxNameModalContent.html',
                controller: 'ConnectModalCtrl'
            });

        modalInstance.result.then(function(remoteBoxName) {
            $scope.uiState.connectInProgress = true;

            var connectPromise = $http.post('/api/boxes/addremotebox',
                {
                    name: remoteBoxName,
                    baseUrl: $scope.remoteBoxBaseURL
                });

            connectPromise.success(function(data) {
                $scope.remoteBoxBaseURL = null;
            });

            connectPromise.error(function(data) {
                $scope.appendErrorMessage(data);
            });

            connectPromise.finally(function() {
                $scope.uiState.connectInProgress = false;
                $scope.callbacks.boxesTable.reloadPage();
            });
        });
    };

    $scope.generateURLButtonClicked = function() {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/generateURLModalContent.html',
                controller: 'GenerateURLModalCtrl',
                size: 'lg'
            });

        modalInstance.result.then(function(remoteBoxName) {
            $scope.uiState.generateURLInProgress = true;

            var generateURLPromise = $http.post('/api/boxes/generatebaseurl', {value: remoteBoxName});

            generateURLPromise.success(function(data) {
                showBaseURLDialog(data.value);
            });

            generateURLPromise.error(function(data) {
                $scope.appendErrorMessage(data);
            });

            generateURLPromise.finally(function() {
                $scope.uiState.generateURLInProgress = false;
                $scope.callbacks.boxesTable.reloadPage();
            });
        });
    };

    // Private functions
    function confirmDeleteBoxes(boxes) {
        var deleteConfirmationText = 'Permanently delete ' + boxes.length + ' boxes?';

        return openConfirmationDeleteModal('Delete Boxes', deleteConfirmationText, function() {
            return deleteBoxes(boxes);
        });
    }

    function deleteBoxes(boxes) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(boxes, function(box) {
            deletePromise = $http.delete('/api/boxes/' + box.id);
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
    $scope.mailBody = function() {
        var bodyText = 'Box connection URL:\n\n' + baseURL;

        return encodeURIComponent(bodyText);
    };

    $scope.closeButtonClicked = function() {
        $modalInstance.dismiss();
    };
})

.controller('ConnectModalCtrl', function($scope, $modalInstance) {
    // Initialization

    // Scope functions
    $scope.connectButtonClicked = function() {
        $modalInstance.close($scope.remoteBoxName);
    };

    $scope.cancelButtonClicked = function() {
        $modalInstance.dismiss();
    };
});